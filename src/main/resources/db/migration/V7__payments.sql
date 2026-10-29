-- Payment / refund transactions, separated from reservations.
CREATE TABLE payment_transaction (
    id                  uuid          PRIMARY KEY,
    reservation_id      uuid          NOT NULL REFERENCES reservation (id),
    type                varchar(10)   NOT NULL,
    status              varchar(10)   NOT NULL,
    amount              numeric(19,4) NOT NULL,
    currency            varchar(3)    NOT NULL,
    provider_reference  varchar(100),
    failure_reason      varchar(255),
    attempts            integer       NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL,
    updated_at          timestamptz   NOT NULL,
    version             bigint        NOT NULL DEFAULT 0,
    CONSTRAINT chk_payment_type CHECK (type IN ('CHARGE','REFUND')),
    CONSTRAINT chk_payment_status CHECK (status IN ('PENDING','UNKNOWN','SUCCEEDED','DECLINED','FAILED')),
    CONSTRAINT chk_payment_amount CHECK (amount >= 0),
    CONSTRAINT chk_payment_currency CHECK (currency ~ '^[A-Z]{3}$')
);
CREATE INDEX ix_payment_reservation ON payment_transaction (reservation_id);
CREATE INDEX ix_payment_unresolved ON payment_transaction (created_at) WHERE status IN ('PENDING','UNKNOWN');
-- one successful charge per reservation
CREATE UNIQUE INDEX ux_payment_one_successful_charge ON payment_transaction (reservation_id)
    WHERE type = 'CHARGE' AND status = 'SUCCEEDED';
