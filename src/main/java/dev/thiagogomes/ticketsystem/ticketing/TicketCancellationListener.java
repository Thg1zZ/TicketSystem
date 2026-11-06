package dev.thiagogomes.ticketsystem.ticketing;

import dev.thiagogomes.ticketsystem.checkout.OrderCancelledEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * In-process and synchronous: runs inside the cancellation transaction, so a cancelled order never keeps a
 * valid ticket. Checkout publishes the event without knowing about ticketing (no package cycle).
 */
@Component
class TicketCancellationListener {

    private final TicketRepository ticketRepository;

    TicketCancellationListener(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @EventListener
    void onOrderCancelled(OrderCancelledEvent event) {
        ticketRepository.cancelForOrder(event.orderId());
    }
}
