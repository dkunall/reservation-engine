-- TIME_RANGE model: a unit (room, vehicle) can't have two ACTIVE allocations with overlapping periods.
CREATE TABLE range_allocation (
    id              uuid         PRIMARY KEY,
    reservation_id  uuid         NOT NULL REFERENCES reservation (id),
    unit_id         uuid         NOT NULL REFERENCES inventory_unit (id),
    period          tstzrange    NOT NULL,
    status          varchar(10)  NOT NULL,
    created_at      timestamptz  NOT NULL,
    released_at     timestamptz,
    CONSTRAINT chk_range_allocation_status CHECK (status IN ('ACTIVE','RELEASED')),
    CONSTRAINT chk_range_allocation_period CHECK (NOT isempty(period) AND lower_inc(period) AND NOT upper_inc(period)),
    CONSTRAINT ex_range_allocation_no_overlap
        EXCLUDE USING gist (unit_id WITH =, period WITH &&) WHERE (status = 'ACTIVE')
);
CREATE INDEX ix_range_allocation_reservation ON range_allocation (reservation_id);
