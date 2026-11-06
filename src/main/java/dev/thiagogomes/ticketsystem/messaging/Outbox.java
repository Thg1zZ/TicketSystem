package dev.thiagogomes.ticketsystem.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Records an event in the caller's transaction: it is published if and only if the business change commits.
 */
@Component
public class Outbox {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public Outbox(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String eventType, UUID aggregateId, Object payload) {
        jdbc.update("INSERT INTO outbox_event (id, aggregate_id, event_type, payload) VALUES (?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), aggregateId, eventType, json.writeValueAsString(payload));
    }
}
