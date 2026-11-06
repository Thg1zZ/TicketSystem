-- Transactional Outbox: o evento é gravado na mesma transação da mudança de estado e publicado depois
CREATE TABLE outbox_event (
    id           UUID PRIMARY KEY,
    aggregate_id UUID        NOT NULL,
    event_type   VARCHAR(50) NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX ix_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;
