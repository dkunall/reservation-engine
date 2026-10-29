-- Reservation aggregate. Resource type / inventory model are snapshotted at hold time.
CREATE TABLE reservation (
    id               uuid          PRIMARY KEY,
    user_id          varchar(100)  NOT NULL,
    resource_id      uuid          NOT NULL REFERENCES resource (id),
    resource_type    varchar(50)   NOT NULL,
    inventory_model  varchar(30)   NOT NULL,
    status           varchar(20)   NOT NULL,
    quantity         integer       NOT NULL,
    starts_at        timestamptz,
    ends_at          timestamptz,
    slot_id          uuid,
    unit_codes       jsonb         NOT NULL DEFAULT '[]'::jsonb,
    total_amount     numeric(19,4) NOT NULL,
    currency         varchar(3)    NOT NULL,
    hold_expires_at  timestamptz   NOT NULL,
    confirmed_at     timestamptz,
    cancelled_at     timestamptz,
    expired_at       timestamptz,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT chk_reservation_status CHECK (status IN ('HELD','PAYMENT_PENDING','CONFIRMED','CANCELLED','EXPIRED')),
    CONSTRAINT chk_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT chk_reservation_amount CHECK (total_amount >= 0),
    CONSTRAINT chk_reservation_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_reservation_period CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT chk_reservation_confirmed_at CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL)
);

CREATE INDEX ix_reservation_user_created ON reservation (user_id, created_at DESC);
CREATE INDEX ix_reservation_resource ON reservation (resource_id);
-- sweeper scans only live holds
CREATE INDEX ix_reservation_held_expiry ON reservation (hold_expires_at) WHERE status = 'HELD';
-- reconciliation scans only in-flight payments
CREATE INDEX ix_reservation_payment_pending ON reservation (updated_at) WHERE status = 'PAYMENT_PENDING';

-- Append-only audit trail of state transitions
CREATE TABLE reservation_audit (
    id              bigserial     PRIMARY KEY,
    reservation_id  uuid          NOT NULL REFERENCES reservation (id),
    from_status     varchar(20),
    to_status       varchar(20)   NOT NULL,
    actor           varchar(100)  NOT NULL,
    reason          varchar(255),
    correlation_id  varchar(100),
    occurred_at     timestamptz   NOT NULL
);
CREATE INDEX ix_reservation_audit_reservation ON reservation_audit (reservation_id, occurred_at);
