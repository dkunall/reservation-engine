-- TIME_SLOT model: discrete slots (appointments) each with its own capacity counter.
CREATE TABLE time_slot (
    id           uuid         PRIMARY KEY,
    resource_id  uuid         NOT NULL REFERENCES resource (id),
    starts_at    timestamptz  NOT NULL,
    ends_at      timestamptz  NOT NULL,
    capacity     integer      NOT NULL,
    reserved     integer      NOT NULL DEFAULT 0,
    CONSTRAINT chk_time_slot_period CHECK (ends_at > starts_at),
    CONSTRAINT chk_time_slot_capacity CHECK (capacity > 0),
    CONSTRAINT chk_time_slot_reserved CHECK (reserved >= 0 AND reserved <= capacity),
    CONSTRAINT uq_time_slot_start UNIQUE (resource_id, starts_at)
);

CREATE TABLE slot_allocation (
    id              uuid         PRIMARY KEY,
    reservation_id  uuid         NOT NULL REFERENCES reservation (id),
    slot_id         uuid         NOT NULL REFERENCES time_slot (id),
    quantity        integer      NOT NULL,
    status          varchar(10)  NOT NULL,
    created_at      timestamptz  NOT NULL,
    released_at     timestamptz,
    CONSTRAINT chk_slot_allocation_quantity CHECK (quantity > 0),
    CONSTRAINT chk_slot_allocation_status CHECK (status IN ('ACTIVE','RELEASED'))
);
CREATE INDEX ix_slot_allocation_reservation ON slot_allocation (reservation_id);
CREATE UNIQUE INDEX ux_slot_allocation_active ON slot_allocation (reservation_id) WHERE status = 'ACTIVE';
