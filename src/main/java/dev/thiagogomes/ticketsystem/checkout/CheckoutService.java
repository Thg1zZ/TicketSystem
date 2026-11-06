package dev.thiagogomes.ticketsystem.checkout;

import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.messaging.Messaging;
import dev.thiagogomes.ticketsystem.messaging.Outbox;
import dev.thiagogomes.ticketsystem.payment.FakePaymentGateway;
import dev.thiagogomes.ticketsystem.payment.PaymentResult;
import dev.thiagogomes.ticketsystem.reservation.HoldService;
import dev.thiagogomes.ticketsystem.reservation.SeatHold;
import dev.thiagogomes.ticketsystem.reservation.SeatInventoryRepository;
import dev.thiagogomes.ticketsystem.shared.error.BusinessRuleException;
import dev.thiagogomes.ticketsystem.shared.error.ConflictException;
import dev.thiagogomes.ticketsystem.shared.error.PaymentRequiredException;
import dev.thiagogomes.ticketsystem.shared.error.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Checkout in three short transactions with the slow payment call outside of all of them:
 * <ol>
 *   <li>place (or find) the order for this Idempotency-Key and lock the hold;</li>
 *   <li>charge the gateway, keyed by the order id, so any retry reuses the same charge;</li>
 *   <li>confirm (seats SOLD) or fail (hold released, refund if the seats were lost meanwhile).</li>
 * </ol>
 * Every step is safe to repeat, so a replay of a still-pending order simply resumes it.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    private static final String DECLINED_REPLAY = "Payment was not approved for this order";
    private static final String SEATS_LOST = "Seats were released before the payment completed; the charge was refunded";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SeatInventoryRepository seatInventory;
    private final HoldService holdService;
    private final FakePaymentGateway paymentGateway;
    private final Outbox outbox;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CheckoutService(OrderRepository orderRepository,
                           OrderItemRepository orderItemRepository,
                           SeatInventoryRepository seatInventory,
                           HoldService holdService,
                           FakePaymentGateway paymentGateway,
                           Outbox outbox,
                           TransactionTemplate transaction,
                           Clock clock) {
        this.outbox = outbox;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.seatInventory = seatInventory;
        this.holdService = holdService;
        this.paymentGateway = paymentGateway;
        this.transaction = transaction;
        this.clock = clock;
    }

    public CheckoutResult checkout(UUID holdId, String userId, String idempotencyKey) {
        Order order = placeOrResume(holdId, userId, idempotencyKey);
        if (order.isSettled()) {
            return result(order.getId(), true, DECLINED_REPLAY);
        }
        String failureReason = settle(order, charge(order));
        return result(order.getId(), false, failureReason);
    }

    /**
     * Drives orders left in PENDING_PAYMENT (client gave up, instance crashed) to a final state.
     * Reconciliation never creates a charge: it only finishes what a lost request started.
     */
    public int resumePendingOrdersOlderThan(Duration age) {
        List<UUID> stale = orderRepository.findPendingIdsUpdatedBefore(clock.instant().minus(age));
        for (UUID orderId : stale) {
            try {
                orderRepository.findById(orderId).filter(order -> !order.isSettled()).ifPresent(this::reconcile);
            } catch (RuntimeException e) {
                log.error("Could not resume pending order {}", orderId, e);
            }
        }
        return stale.size();
    }

    private void reconcile(Order order) {
        paymentGateway.findCharge(order.paymentKey()).ifPresentOrElse(
                payment -> settle(order, payment),
                () -> markFailed(order)); // never charged: the client gave up before paying
    }

    private Order placeOrResume(UUID holdId, String userId, String idempotencyKey) {
        try {
            return transaction.execute(status -> findSameRequest(userId, idempotencyKey, holdId)
                    .orElseGet(() -> place(holdId, userId, idempotencyKey)));
        } catch (ConflictException | DataIntegrityViolationException raced) {
            // A concurrent request with the same key may have placed the order first: resume it instead of failing
            return transaction.execute(status -> findSameRequest(userId, idempotencyKey, holdId)).orElseThrow(() -> raced);
        }
    }

    private Optional<Order> findSameRequest(String userId, String idempotencyKey, UUID holdId) {
        return orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(order -> {
            if (!order.belongsToHold(holdId)) {
                throw new BusinessRuleException("Idempotency-Key was already used for a different checkout");
            }
            return order;
        });
    }

    private Order place(UUID holdId, String userId, String idempotencyKey) {
        SeatHold hold = holdService.convertForCheckout(holdId, userId);
        List<Seat> seats = seatInventory.findHeldBy(hold.getId());
        if (seats.isEmpty()) {
            throw new ConflictException("Hold has no seats left");
        }
        return orderRepository.saveAndFlush(Order.place(userId, hold.getId(), idempotencyKey, seats, clock.instant()));
    }

    /**
     * @return null when confirmed, otherwise why the order failed
     */
    private String settle(Order order, PaymentResult payment) {
        if (!payment.approved()) {
            markFailed(order);
            return payment.declineReason();
        }
        try {
            if (Boolean.TRUE.equals(transaction.execute(status -> confirm(order, payment.reference())))) {
                return null;
            }
        } catch (SeatsLostException | DataIntegrityViolationException lost) {
            log.warn("Order {} lost its seats during payment, refunding", order.getId());
        }
        // Approved but not confirmed: the money has to go back (refunds are idempotent)
        paymentGateway.refund(order.paymentKey());
        markFailed(order);
        return SEATS_LOST;
    }

    /**
     * A gateway error leaves the order PENDING: retrying with the same Idempotency-Key (or the recovery job)
     * reuses the same gateway key, so it can never turn into a second charge.
     */
    private PaymentResult charge(Order order) {
        try {
            return paymentGateway.charge(order.paymentKey(), order.getTotalAmount());
        } catch (RuntimeException gatewayFailure) {
            throw new ServiceUnavailableException(
                    "Payment outcome unknown; retry with the same Idempotency-Key", gatewayFailure);
        }
    }

    /**
     * @return false when a concurrent request already settled the order as failed, so this charge must be refunded
     */
    private boolean confirm(Order order, String paymentReference) {
        if (orderRepository.confirm(order.getId(), paymentReference, clock.instant()) == 0) {
            return orderRepository.findById(order.getId())
                    .map(settled -> settled.getStatus() != OrderStatus.PAYMENT_FAILED)
                    .orElse(false);
        }
        int expectedSeats = orderRepository.findWithItemsById(order.getId()).orElseThrow().getItems().size();
        if (seatInventory.sellHeldBy(order.getHoldId()) != expectedSeats) {
            throw new SeatsLostException();
        }
        orderItemRepository.setActive(order.getId(), true);
        outbox.append(Messaging.ORDER_CONFIRMED, order.getId(), new OrderConfirmedEvent(order.getId(), order.getUserId()));
        return true;
    }

    private void markFailed(Order order) {
        transaction.executeWithoutResult(status -> {
            if (orderRepository.transition(order.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_FAILED, clock.instant()) == 1) {
                holdService.releaseAfterFailedCheckout(order.getHoldId());
            }
        });
    }

    private CheckoutResult result(UUID orderId, boolean replayed, String failureReason) {
        OrderResponse order = transaction.execute(status ->
                OrderResponse.from(orderRepository.findWithItemsById(orderId).orElseThrow()));
        if (order.status() == OrderStatus.PAYMENT_FAILED) {
            throw new PaymentRequiredException(Objects.requireNonNullElse(failureReason, DECLINED_REPLAY),
                    Map.of("orderId", orderId));
        }
        return new CheckoutResult(order, replayed);
    }

    /**
     * Rolls back the confirmation when the hold no longer owns every seat.
     */
    private static final class SeatsLostException extends RuntimeException {
    }
}
