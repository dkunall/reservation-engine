-- Flash-sale / hot-inventory mode for CAPACITY: optional sharded counters.
ALTER TABLE capacity_inventory ADD COLUMN bucket_count integer NOT NULL DEFAULT 0;
ALTER TABLE capacity_inventory ADD CONSTRAINT chk_capacity_bucket_count CHECK (bucket_count BETWEEN 0 AND 1024);

CREATE TABLE capacity_bucket (
    resource_id  uuid     NOT NULL REFERENCES capacity_inventory (resource_id),
    bucket_no    integer  NOT NULL,
    capacity     integer  NOT NULL,
    reserved     integer  NOT NULL DEFAULT 0,
    PRIMARY KEY (resource_id, bucket_no),
    CONSTRAINT chk_bucket_no CHECK (bucket_no >= 0),
    CONSTRAINT chk_bucket_capacity CHECK (capacity >= 0),
    CONSTRAINT chk_bucket_reserved CHECK (reserved >= 0),
    CONSTRAINT chk_bucket_not_oversold CHECK (reserved <= capacity)
);

ALTER TABLE capacity_allocation ADD COLUMN bucket_no integer;
ALTER TABLE capacity_allocation ADD CONSTRAINT fk_capacity_allocation_bucket
    FOREIGN KEY (resource_id, bucket_no) REFERENCES capacity_bucket (resource_id, bucket_no);
-- a reservation may now draw from several buckets (one active row per bucket)
DROP INDEX ux_capacity_allocation_active;
CREATE UNIQUE INDEX ux_capacity_allocation_active
    ON capacity_allocation (reservation_id, coalesce(bucket_no, -1)) WHERE status = 'ACTIVE';

-- Invariant: sum(bucket.capacity) = capacity_inventory.capacity for sharded inventories. Together with the
-- per-bucket CHECK (reserved <= capacity) this guarantees the total can never be oversold.
-- Deferred constraint triggers only fire when capacity columns change (never on the hot reserved updates).
CREATE FUNCTION check_capacity_bucket_sum() RETURNS trigger AS $$
DECLARE
    rid uuid := COALESCE(NEW.resource_id, OLD.resource_id);
    total integer;
    buckets integer;
    bucket_sum integer;
BEGIN
    SELECT capacity, bucket_count INTO total, buckets FROM capacity_inventory WHERE resource_id = rid;
    IF buckets IS NULL OR buckets = 0 THEN
        RETURN NULL;
    END IF;
    SELECT COALESCE(SUM(capacity), 0) INTO bucket_sum FROM capacity_bucket WHERE resource_id = rid;
    IF bucket_sum <> total THEN
        RAISE EXCEPTION 'capacity bucket sum % does not match capacity % for resource %', bucket_sum, total, rid
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_capacity_bucket_sum
    AFTER INSERT OR DELETE OR UPDATE OF capacity ON capacity_bucket
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_capacity_bucket_sum();

CREATE CONSTRAINT TRIGGER trg_capacity_inventory_bucket_sum
    AFTER UPDATE OF capacity, bucket_count ON capacity_inventory
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_capacity_bucket_sum();
