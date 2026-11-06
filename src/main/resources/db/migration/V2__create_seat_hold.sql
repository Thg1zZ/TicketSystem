CREATE TABLE seat_hold (
    id         UUID PRIMARY KEY,
    event_id   BIGINT      NOT NULL REFERENCES event (id),
    user_id    VARCHAR(64) NOT NULL,
    status     VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'CONVERTED', 'EXPIRED', 'RELEASED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Garante no banco: 1 hold ativo por usuário por evento (inclusive sob duplo clique concorrente)
CREATE UNIQUE INDEX ux_seat_hold_active_user ON seat_hold (event_id, user_id) WHERE status = 'ACTIVE';

ALTER TABLE seat ADD CONSTRAINT fk_seat_hold FOREIGN KEY (hold_id) REFERENCES seat_hold (id);

CREATE INDEX ix_seat_hold_id ON seat (hold_id) WHERE hold_id IS NOT NULL;
CREATE INDEX ix_seat_hold_expiring ON seat_hold (expires_at) WHERE status = 'ACTIVE';
