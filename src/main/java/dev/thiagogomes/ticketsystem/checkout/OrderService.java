package dev.thiagogomes.ticketsystem.checkout;

import dev.thiagogomes.ticketsystem.event.SeatRepository;
import dev.thiagogomes.ticketsystem.messaging.Messaging;
import dev.thiagogomes.ticketsystem.messaging.Outbox;
import dev.thiagogomes.ticketsystem.payment.FakePaymentGateway;
import dev.thiagogomes.ticketsystem.reservation.HoldService;
import dev.thiagogomes.ticketsystem.shared.error.BusinessRuleException;
import dev.thiagogomes.ticketsystem.shared.error.ConflictException;
import dev.thiagogomes.ticketsystem.shared.error.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SeatRepository seatRepository;
    private final HoldService holdService;
    private final FakePaymentGateway paymentGateway;
    private final Outbox outbox;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository,
                        OrderItemRepository orderItemRepository,
                        SeatRepository seatRepository,
                        HoldService holdService,
                        FakePaymentGateway paymentGateway,
                        Outbox outbox,
                        ApplicationEventPublisher events,
                        Clock clock) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.seatRepository = seatRepository;
        this.holdService = holdService;
        this.paymentGateway = paymentGateway;
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Another user's order answers 404, never 403: existence is not revealed.
     */
    @Transactional(readOnly = true)
    public OrderResponse find(UUID orderId, String userId) {
        return orderRepository.findWithItemsByIdAndUserId(orderId, userId)
                .map(OrderResponse::from)
                .orElseThrow(() -> notFound(orderId));
    }

    /**
     * Idempotent: cancelling an already cancelled order returns it unchanged (no second refund).
     * The refund runs last inside the transaction: if it fails, nothing is cancelled and the client can retry;
     * the gateway deduplicates refunds, so a retry after an unlucky commit failure is also safe.
     */
    @Transactional
    public OrderResponse cancel(UUID orderId, String userId) {
        Order order = orderRepository.lockById(orderId)
                .filter(locked -> locked.getUserId().equals(userId))
                .orElseThrow(() -> notFound(orderId));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return OrderResponse.from(order);
        }
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new ConflictException("Only confirmed orders can be cancelled");
        }
        Instant now = clock.instant();
        if (!now.isBefore(orderRepository.findEventStart(orderId).orElseThrow())) {
            throw new BusinessRuleException("The event has already started");
        }

        List<Long> seatIds = order.getItems().stream().map(OrderItem::getSeatId).toList();
        orderRepository.transition(orderId, OrderStatus.CONFIRMED, OrderStatus.CANCELLED, now);
        orderItemRepository.setActive(orderId, false);
        seatRepository.releaseSold(seatIds);
        holdService.forgetSeats(order.getHoldId(), seatIds);

        OrderCancelledEvent cancelled = new OrderCancelledEvent(orderId, userId);
        events.publishEvent(cancelled); // synchronous, same transaction: tickets are voided before commit
        outbox.append(Messaging.ORDER_CANCELLED, orderId, cancelled);
        paymentGateway.refund(order.paymentKey());

        return OrderResponse.from(orderRepository.findWithItemsById(orderId).orElseThrow());
    }

    private static NotFoundException notFound(UUID orderId) {
        return new NotFoundException("Order %s not found".formatted(orderId));
    }
}
