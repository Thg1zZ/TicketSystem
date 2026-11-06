package dev.thiagogomes.ticketsystem.notification;

import dev.thiagogomes.ticketsystem.checkout.OrderCancelledEvent;
import dev.thiagogomes.ticketsystem.messaging.Messaging;
import dev.thiagogomes.ticketsystem.ticketing.TicketsIssuedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulated e-mail. The configurable failure rate exists to watch retries and the DLQ in the RabbitMQ panel.
 * At-least-once delivery means a rare duplicate e-mail; acceptable here, a sent-log table would dedupe it.
 */
@Component
class EmailNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationListener.class);

    private final double failureRate;

    EmailNotificationListener(@Value("${ticket.notification.failure-rate}") double failureRate) {
        this.failureRate = failureRate;
    }

    @RabbitListener(queues = Messaging.TICKETS_ISSUED_EMAIL_QUEUE)
    void onTicketsIssued(TicketsIssuedEvent event) {
        send(event.userId(), "Your %d ticket(s) for order %s are ready".formatted(event.ticketCount(), event.orderId()));
    }

    @RabbitListener(queues = Messaging.ORDER_CANCELLED_EMAIL_QUEUE)
    void onOrderCancelled(OrderCancelledEvent event) {
        send(event.userId(), "Order %s was cancelled and refunded".formatted(event.orderId()));
    }

    private void send(String userId, String subject) {
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new IllegalStateException("Simulated SMTP failure");
        }
        log.info("[simulated e-mail] to={} subject=\"{}\"", userId, subject);
    }
}
