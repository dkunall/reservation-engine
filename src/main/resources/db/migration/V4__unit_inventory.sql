-- Physical units (seats, rooms, vehicles). Shared by EXCLUSIVE_UNIT and TIME_RANGE models.
CREATE TABLE inventory_unit (
    id           uuid          PRIMARY KEY,
    resource_id  uuid          NOT NULL REFERENCES resource (id),
    unit_code    varchar(50)   NOT NULL,
    active       boolean       NOT NULL DEFAULT true,
    created_at   timestamptz   NOT NULL,
    CONSTRAINT uq_inventory_unit_code UNIQUE (resource_id, unit_code)
);

-- EXCLUSIVE_UNIT model: a unit can have at most ONE active allocation (partial unique index).
CREATE TABLE unit_allocation (
    id              uuid         PRIMARY KEY,
    reservation_id  uuid         NOT NULL REFERENCES reservation (id),
    unit_id         uuid         NOT NULL REFERENCES inventory_unit (id),
    status          varchar(10)  NOT NULL,
    created_at      timestamptz  NOT NULL,
    released_at     timestamptz,
    CONSTRAINT chk_unit_allocation_status CHECK (status IN ('ACTIVE','RELEASED'))
);
CREATE UNIQUE INDEX ux_unit_allocation_active_unit ON unit_allocation (unit_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_unit_allocation_reservation ON unit_allocation (reservation_id);
