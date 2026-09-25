-- CapacitySnapshotUpdated is emitted per capacity pool and may contain one or
-- more class snapshots.  snapshotVersion describes the source pool/composition
-- and is not a monotonic version of the segment/date projection: hold,
-- confirmation and release events can legitimately publish different counts
-- with the same value.  The source event timestamp/id therefore orders each
-- class-specific projection.
ALTER TABLE capacity_snapshot_cache
  ADD COLUMN IF NOT EXISTS class_ref text,
  ADD COLUMN IF NOT EXISTS source_occurred_at timestamptz,
  ADD COLUMN IF NOT EXISTS source_event_id text;

-- The old rows mixed all classes and cannot be assigned to a class safely.
-- This table is a disposable event projection and will refill from new events.
DELETE FROM capacity_snapshot_cache
WHERE class_ref IS NULL OR source_occurred_at IS NULL OR source_event_id IS NULL;

ALTER TABLE capacity_snapshot_cache
  ALTER COLUMN snapshot_version TYPE text USING snapshot_version::text,
  ALTER COLUMN class_ref SET NOT NULL,
  ALTER COLUMN source_occurred_at SET NOT NULL,
  ALTER COLUMN source_event_id SET NOT NULL;

ALTER TABLE capacity_snapshot_cache
  DROP CONSTRAINT IF EXISTS capacity_snapshot_cache_total_capacity_check;

ALTER TABLE capacity_snapshot_cache
  ADD CONSTRAINT capacity_snapshot_cache_total_capacity_check
  CHECK (total_capacity >= 0);

ALTER TABLE capacity_snapshot_cache
  DROP CONSTRAINT IF EXISTS capacity_snapshot_cache_pkey;

ALTER TABLE capacity_snapshot_cache
  ADD CONSTRAINT capacity_snapshot_cache_pkey
  PRIMARY KEY (segment_ref, departure_date, class_ref);

DROP INDEX IF EXISTS idx_capacity_snapshot_cache_departure;
CREATE INDEX idx_capacity_snapshot_cache_departure
  ON capacity_snapshot_cache(departure_date, segment_ref, class_ref);
