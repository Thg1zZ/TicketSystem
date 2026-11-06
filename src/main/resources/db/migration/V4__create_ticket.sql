CREATE TABLE ticket (
    id        UUID PRIMARY KEY,
    order_id  UUID        NOT NULL REFERENCES orders (id),
    seat_id   BIGINT      NOT NULL REFERENCES seat (id),
    code      VARCHAR(64) NOT NULL,
    status    VARCHAR(10) NOT NULL CHECK (status IN ('VALID', 'CANCELLED')),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ticket_code UNIQUE (code),
    -- Mensagem reentregue não emite ingresso em dobro (consumidor idempotente)
    CONSTRAINT uk_ticket_order_seat UNIQUE (order_id, seat_id)
);
