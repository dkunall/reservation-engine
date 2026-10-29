-- Itinerary: groups several reservations (one per item) that are held / confirmed / cancelled / expired as a unit.
CREATE TABLE itinerary (
    id               uuid          PRIMARY KEY,
    user_id          varchar(100)  NOT NULL,
    status           varchar(20)   NOT NULL,
    item_count       integer       NOT NULL,
    total_amount     numeric(19,4) NOT NULL,
    currency         varchar(3)    NOT NULL,
    hold_expires_at  timestamptz   NOT NULL,
    confirmed_at     timestamptz,
    cancelled_at     timestamptz,
    expired_at       timestamptz,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT chk_itinerary_status CHECK (status IN ('HELD','PAYMENT_PENDING','CONFIRMED','CANCELLED','EXPIRED')),
    CONSTRAINT chk_itinerary_items CHECK (item_count BETWEEN 1 AND 20),
    CONSTRAINT chk_itinerary_amount CHECK (total_amount >= 0),
    CONSTRAINT chk_itinerary_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_itinerary_confirmed_at CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL)
);
CREATE INDEX ix_itinerary_user_created ON itinerary (user_id, created_at DESC);
CREATE INDEX ix_itinerary_held_expiry ON itinerary (hold_expires_at) WHERE status = 'HELD';
CREATE INDEX ix_itinerary_payment_pending ON itinerary (updated_at) WHERE status = 'PAYMENT_PENDING';

ALTER TABLE reservation ADD COLUMN itinerary_id uuid REFERENCES itinerary (id);
CREATE INDEX ix_reservation_itinerary ON reservation (itinerary_id) WHERE itinerary_id IS NOT NULL;

-- The standalone expiry sweeper only handles reservations that are NOT part of an itinerary
-- (itineraries expire as a whole through their own sweeper).
DROP INDEX ix_reservation_held_expiry;
CREATE INDEX ix_reservation_held_expiry ON reservation (hold_expires_at) WHERE status = 'HELD' AND itinerary_id IS NULL;

-- A payment belongs to exactly one owner: a standalone reservation or an itinerary.
ALTER TABLE payment_transaction ALTER COLUMN reservation_id DROP NOT NULL;
ALTER TABLE payment_transaction ADD COLUMN itinerary_id uuid REFERENCES itinerary (id);
ALTER TABLE payment_transaction ADD CONSTRAINT chk_payment_owner CHECK (num_nonnulls(reservation_id, itinerary_id) = 1);
CREATE INDEX ix_payment_itinerary ON payment_transaction (itinerary_id) WHERE itinerary_id IS NOT NULL;
CREATE UNIQUE INDEX ux_payment_one_successful_itinerary_charge ON payment_transaction (itinerary_id)
    WHERE type = 'CHARGE' AND status = 'SUCCEEDED' AND itinerary_id IS NOT NULL;
