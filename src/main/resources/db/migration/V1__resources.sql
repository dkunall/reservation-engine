-- Resource identity (catalog). Inventory lives in model-specific tables.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE resource (
    id              uuid          PRIMARY KEY,
    type_code       varchar(50)   NOT NULL,
    inventory_model varchar(30)   NOT NULL,
    name            varchar(200)  NOT NULL,
    description     varchar(2000),
    attributes      jsonb         NOT NULL DEFAULT '{}'::jsonb,
    price_amount    numeric(19,4) NOT NULL,
    currency        varchar(3)    NOT NULL,
    active          boolean       NOT NULL DEFAULT true,
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL,
    version         bigint        NOT NULL DEFAULT 0,
    CONSTRAINT chk_resource_price_non_negative CHECK (price_amount >= 0),
    CONSTRAINT chk_resource_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_resource_type_code CHECK (type_code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT chk_resource_inventory_model CHECK (inventory_model ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE INDEX ix_resource_type_created ON resource (type_code, created_at DESC);
CREATE INDEX ix_resource_created ON resource (created_at DESC);
