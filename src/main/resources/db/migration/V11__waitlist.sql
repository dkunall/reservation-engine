-- Waitlist entries (FIFO by requested_at) that are auto-promoted to HELD reservations when inventory is released.
CREATE TABLE waitlist_entry (
    id                       uuid          PRIMARY KEY,
    user_id                  varchar(100)  NOT NULL,
    resource_id              uuid          NOT NULL REFERENCES resource (id),
    status                   varchar(15)   NOT NULL,
    quantity                 integer       NOT NULL,
    unit_codes               jsonb         NOT NULL DEFAULT '[]'::jsonb,
    slot_id                  uuid,
    starts_at                timestamptz,
    ends_at                  timestamptz,
    requested_at             timestamptz   NOT NULL,
    expires_at               timestamptz   NOT NULL,
    promoted_reservation_id  uuid          REFERENCES reservation (id),
    promoted_at              timestamptz,
    attempts                 integer       NOT NULL DEFAULT 0,
    last_error               varchar(255),
    created_at               timestamptz   NOT NULL,
    updated_at               timestamptz   NOT NULL,
    version                  bigint        NOT NULL DEFAULT 0,
    CONSTRAINT chk_waitlist_status CHECK (status IN ('WAITING','PROMOTED','EXPIRED','CANCELLED')),
    CONSTRAINT chk_waitlist_quantity CHECK (quantity > 0),
    CONSTRAINT chk_waitlist_period CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT chk_waitlist_expiry CHECK (expires_at > requested_at),
    CONSTRAINT chk_waitlist_promoted CHECK (status <> 'PROMOTED' OR promoted_reservation_id IS NOT NULL)
);
-- FIFO scan per resource over live entries only
CREATE INDEX ix_waitlist_fifo ON waitlist_entry (resource_id, requested_at, id) WHERE status = 'WAITING';
CREATE INDEX ix_waitlist_user ON waitlist_entry (user_id, created_at DESC);
CREATE INDEX ix_waitlist_expiry ON waitlist_entry (expires_at) WHERE status = 'WAITING';
-- a reservation can be the promotion of at most one entry
CREATE UNIQUE INDEX ux_waitlist_promoted_reservation ON waitlist_entry (promoted_reservation_id)
    WHERE promoted_reservation_id IS NOT NULL;

-- "Inventory may have become available for resource X" signals, written in the same transaction as the
-- release (or waitlist join) and consumed by the promoter with FOR UPDATE SKIP LOCKED.
CREATE TABLE waitlist_signal (
    id            bigserial    PRIMARY KEY,
    resource_id   uuid         NOT NULL REFERENCES resource (id),
    reason        varchar(30)  NOT NULL,
    created_at    timestamptz  NOT NULL,
    processed_at  timestamptz
);
CREATE INDEX ix_waitlist_signal_pending ON waitlist_signal (id) WHERE processed_at IS NULL;
