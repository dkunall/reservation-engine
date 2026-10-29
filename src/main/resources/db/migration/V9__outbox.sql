CREATE TABLE outbox_event (
    id              uuid          PRIMARY KEY,
    aggregate_type  varchar(50)   NOT NULL,
    aggregate_id    uuid          NOT NULL,
    event_type      varchar(100)  NOT NULL,
    payload         jsonb         NOT NULL,
    status          varchar(10)   NOT NULL,
    attempts        integer       NOT NULL DEFAULT 0,
    last_error      varchar(1000),
    created_at      timestamptz   NOT NULL,
    sent_at         timestamptz,
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','SENT','FAILED'))
);
CREATE INDEX ix_outbox_pending ON outbox_event (created_at) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_aggregate ON outbox_event (aggregate_id);
