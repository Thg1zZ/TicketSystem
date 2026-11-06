CREATE TABLE orders (
    id                UUID PRIMARY KEY,
    user_id           VARCHAR(64)    NOT NULL,
    hold_id           UUID           NOT NULL REFERENCES seat_hold (id),
    idempotency_key   VARCHAR(100)   NOT NULL,
    total_amount      NUMERIC(10, 2) NOT NULL CHECK (total_amount > 0),
    payment_reference VARCHAR(100),
    status            VARCHAR(20)    NOT NULL
        CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'PAYMENT_FAILED', 'CANCELLED')),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_orders_hold UNIQUE (hold_id),
    -- Repetir a requisição com a mesma chave nunca cria um segundo pedido
    CONSTRAINT uk_orders_user_idempotency UNIQUE (user_id, idempotency_key)
);

CREATE SEQUENCE order_item_seq INCREMENT BY 50;

CREATE TABLE order_item (
    id       BIGINT PRIMARY KEY,
    order_id UUID           NOT NULL REFERENCES orders (id),
    seat_id  BIGINT         NOT NULL REFERENCES seat (id),
    price    NUMERIC(10, 2) NOT NULL CHECK (price > 0),
    active   BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_order_item_order_seat UNIQUE (order_id, seat_id)
);

-- Última barreira contra venda dupla: um assento só pode estar em UM item ativo (vendido) por vez
CREATE UNIQUE INDEX ux_order_item_active_seat ON order_item (seat_id) WHERE active;
