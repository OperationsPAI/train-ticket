ALTER TABLE voided_entitlements ALTER COLUMN event_id DROP NOT NULL;
ALTER TABLE voided_entitlements ADD COLUMN IF NOT EXISTS ticket_snapshot_version bigint;
ALTER TABLE voided_entitlements ADD CONSTRAINT void_fact_has_source
    CHECK (event_id IS NOT NULL OR ticket_snapshot_version IS NOT NULL);
