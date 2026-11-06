-- Sequences com INCREMENT 50 permitem ao Hibernate reservar IDs em lote e agrupar INSERTs (JDBC batch)
CREATE SEQUENCE event_seq INCREMENT BY 50;
CREATE SEQUENCE sector_seq INCREMENT BY 50;
CREATE SEQUENCE seat_seq INCREMENT BY 50;

CREATE TABLE event (
    id         BIGINT PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    venue      VARCHAR(200) NOT NULL,
    starts_at  TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sector (
    id       BIGINT PRIMARY KEY,
    event_id BIGINT         NOT NULL REFERENCES event (id),
    name     VARCHAR(100)   NOT NULL,
    price    NUMERIC(10, 2) NOT NULL CHECK (price > 0),
    CONSTRAINT uk_sector_event_name UNIQUE (event_id, name)
);

CREATE TABLE seat (
    id          BIGINT PRIMARY KEY,
    sector_id   BIGINT      NOT NULL REFERENCES sector (id),
    row_label   VARCHAR(5)  NOT NULL,
    seat_number INT         NOT NULL CHECK (seat_number > 0),
    status      VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD')),
    hold_id     UUID,
    held_until  TIMESTAMPTZ,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_seat_position UNIQUE (sector_id, row_label, seat_number)
);

-- FK de seat.hold_id para seat_hold entra na V2, junto com a tabela de holds
CREATE INDEX ix_seat_held_until ON seat (held_until) WHERE status = 'HELD';
