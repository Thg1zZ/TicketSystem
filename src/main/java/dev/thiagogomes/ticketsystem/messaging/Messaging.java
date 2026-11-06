package dev.thiagogomes.ticketsystem.messaging;

/**
 * Names shared by producers (outbox) and consumers (listeners).
 */
public final class Messaging {

    public static final String EXCHANGE = "ticket.events";
    public static final String DEAD_LETTER_EXCHANGE = "ticket.events.dlx";

    public static final String ORDER_CONFIRMED = "order.confirmed";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String TICKETS_ISSUED = "tickets.issued";

    public static final String TICKET_ISSUANCE_QUEUE = "ticketing.order-confirmed";
    public static final String TICKETS_ISSUED_EMAIL_QUEUE = "notification.tickets-issued";
    public static final String ORDER_CANCELLED_EMAIL_QUEUE = "notification.order-cancelled";

    private Messaging() {
    }

    public static String deadLetterQueue(String queue) {
        return queue + ".dlq";
    }
}
