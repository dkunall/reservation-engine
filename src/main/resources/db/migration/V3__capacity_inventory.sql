-- CAPACITY model: a counter per resource (GA events, bus seat counts...)
CREATE TABLE capacity_inventory (
    resource_id  uuid     PRIMARY KEY REFERENCES resource (id),
    capacity     integer  NOT NULL,
    reserved     integer  NOT NULL DEFAULT 0,
    CONSTRAINT chk_capacity_non_negative CHECK (capacity >= 0),
    CONSTRAINT chk_capacity_reserved_non_negative CHECK (reserved >= 0),
    CONSTRAINT chk_capacity_not_oversold CHECK (reserved <= capacity)
);

CREATE TABLE capacity_allocation (
    id              uuid         PRIMARY KEY,
    reservation_id  uuid         NOT NULL REFERENCES reservation (id),
    resource_id     uuid         NOT NULL REFERENCES capacity_inventory (resource_id),
    quantity        integer      NOT NULL,
    status          varchar(10)  NOT NULL,
    created_at      timestamptz  NOT NULL,
    released_at     timestamptz,
    CONSTRAINT chk_capacity_allocation_quantity CHECK (quantity > 0),
    CONSTRAINT chk_capacity_allocation_status CHECK (status IN ('ACTIVE','RELEASED'))
);
CREATE INDEX ix_capacity_allocation_reservation ON capacity_allocation (reservation_id);
-- at most one active capacity allocation per reservation
CREATE UNIQUE INDEX ux_capacity_allocation_active ON capacity_allocation (reservation_id) WHERE status = 'ACTIVE';
