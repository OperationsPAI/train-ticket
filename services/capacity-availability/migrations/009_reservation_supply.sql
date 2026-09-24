CREATE TABLE IF NOT EXISTS reservation_supply_facts (
    segment_booking_id text PRIMARY KEY,
    scheduled_service_ref text NOT NULL,
    service_date date NOT NULL,
    class_ref text NOT NULL,
    data jsonb NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS reservation_supply_partition_idx
    ON reservation_supply_facts(scheduled_service_ref, service_date, class_ref);

CREATE TABLE IF NOT EXISTS capacity_supply_observations (
    inventory_pool_id text PRIMARY KEY,
    seat_map_id text NOT NULL,
    seat_map_version bigint NOT NULL,
    sellable_capacity integer NOT NULL CHECK (sellable_capacity >= 0),
    data jsonb NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT now()
);
