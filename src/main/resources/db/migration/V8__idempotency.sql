CREATE TABLE idempotency_record (
    id               bigserial     PRIMARY KEY,
    user_id          varchar(100)  NOT NULL,
    idem_key         varchar(255)  NOT NULL,
    operation        varchar(50)   NOT NULL,
    request_hash     varchar(64)   NOT NULL,
    status           varchar(15)   NOT NULL,
    response_status  integer,
    response_body    jsonb,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    expires_at       timestamptz   NOT NULL,
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idem_key),
    CONSTRAINT chk_idempotency_status CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    CONSTRAINT chk_idempotency_completed CHECK (status <> 'COMPLETED' OR response_status IS NOT NULL)
);
CREATE INDEX ix_idempotency_expires ON idempotency_record (expires_at);
