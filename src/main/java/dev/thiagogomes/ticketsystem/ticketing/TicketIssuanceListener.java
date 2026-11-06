package dev.thiagogomes.ticketsystem.ticketing;

import dev.thiagogomes.ticketsystem.checkout.OrderConfirmedEvent;
import dev.thiagogomes.ticketsystem.messaging.Messaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
class TicketIssuanceListener {

    private static final Logger log = LoggerFactory.getLogger(TicketIssuanceListener.class);

    private final TicketIssuanceService issuanceService;

    TicketIssuanceListener(TicketIssuanceService issuanceService) {
        this.issuanceService = issuanceService;
    }

    /**
     * An exception here triggers the retry policy; after the last attempt the message goes to the DLQ.
     */
    @RabbitListener(queues = Messaging.TICKET_ISSUANCE_QUEUE)
    void onOrderConfirmed(OrderConfirmedEvent event) {
        int issued = issuanceService.issueFor(event.orderId());
        log.info("Issued {} tickets for order {}", issued, event.orderId());
    }
}
