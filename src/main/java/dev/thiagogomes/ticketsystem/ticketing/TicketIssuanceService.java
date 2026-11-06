package dev.thiagogomes.ticketsystem.ticketing;

import dev.thiagogomes.ticketsystem.checkout.Order;
import dev.thiagogomes.ticketsystem.checkout.OrderItem;
import dev.thiagogomes.ticketsystem.checkout.OrderRepository;
import dev.thiagogomes.ticketsystem.checkout.OrderStatus;
import dev.thiagogomes.ticketsystem.messaging.Messaging;
import dev.thiagogomes.ticketsystem.messaging.Outbox;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class TicketIssuanceService {

    private static final Logger log = LoggerFactory.getLogger(TicketIssuanceService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_BYTES = 18;

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final Outbox outbox;
    private final MeterRegistry meters;

    public TicketIssuanceService(OrderRepository orderRepository, TicketRepository ticketRepository, Outbox outbox,
                                 MeterRegistry meters) {
        this.meters = meters;
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.outbox = outbox;
    }

    /**
     * Idempotent: safe under redelivery. Locks the order so a concurrent cancellation either happens first
     * (nothing is issued) or waits for the tickets to exist (and then cancels them).
     */
    @Transactional
    public int issueFor(UUID orderId) {
        Order order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order %s not found".formatted(orderId)));
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            log.info("Order {} is {}, no tickets issued", orderId, order.getStatus());
            return 0;
        }

        int issued = 0;
        for (OrderItem item : order.getItems()) {
            if (item.isActive()) {
                issued += ticketRepository.insertIfAbsent(UUID.randomUUID(), orderId, item.getSeatId(), newCode());
            }
        }
        if (issued > 0) {
            meters.counter("tickets.issued").increment(issued);
            outbox.append(Messaging.TICKETS_ISSUED, orderId, new TicketsIssuedEvent(orderId, order.getUserId(), issued));
        }
        return issued;
    }

    /**
     * 144 random bits, URL-safe: the code is what the gate scanner trusts, so it must not be guessable.
     */
    private static String newCode() {
        byte[] bytes = new byte[CODE_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
