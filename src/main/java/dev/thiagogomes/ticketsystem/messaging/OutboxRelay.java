package dev.thiagogomes.ticketsystem.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Publishes pending outbox rows and marks them as sent. Delivery is at-least-once: a crash after the broker
 * confirms but before the UPDATE commits re-sends the batch, so consumers must be idempotent.
 */
// ponytail: published rows are never deleted; add a retention DELETE when the table grows
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;
    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;

    OutboxRelay(JdbcTemplate jdbc, RabbitTemplate rabbit, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        // Evaluated on each scrape; ix_outbox_unpublished keeps it cheap. A growing value means the broker is behind.
        Gauge.builder("outbox.pending", this::countPending).register(meters);
    }

    private long countPending() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Long.class);
    }

    /**
     * SKIP LOCKED lets several instances relay in parallel without sending the same row twice.
     */
    @Scheduled(fixedDelayString = "${ticket.outbox.relay-interval}")
    @Transactional
    public void relayPending() {
        List<PendingEvent> pending = jdbc.query("""
                        SELECT id, event_type, payload::text FROM outbox_event
                        WHERE published_at IS NULL
                        ORDER BY created_at
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                (rs, row) -> new PendingEvent(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
                BATCH_SIZE);
        if (pending.isEmpty()) {
            return;
        }

        try {
            rabbit.invoke(channel -> {
                pending.forEach(event -> channel.send(Messaging.EXCHANGE, event.type(), toMessage(event)));
                channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
                return null;
            });
        } catch (AmqpException brokerUnavailable) {
            // Rows stay pending and the next run retries; one line instead of a stack trace every half second
            log.warn("Outbox relay paused, {} events pending: {}", pending.size(), brokerUnavailable.getMessage());
            return;
        }

        jdbc.batchUpdate("UPDATE outbox_event SET published_at = now() WHERE id = ?",
                pending.stream().map(event -> new Object[]{event.id()}).toList());
    }

    private static Message toMessage(PendingEvent event) {
        return MessageBuilder.withBody(event.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(event.id().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }

    private record PendingEvent(UUID id, String type, String payload) {
    }
}
