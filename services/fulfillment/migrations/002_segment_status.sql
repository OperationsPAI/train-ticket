-- Segment status declarations (REQ-125). New table ships as a NEW
-- migration: editing an applied migration is skipped on existing databases.
CREATE TABLE IF NOT EXISTS segment_status_records (
  id         text PRIMARY KEY,
  command_id text NOT NULL UNIQUE,
  data       jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_segment_status_records_segment ON segment_status_records ((data->>'segmentRef'));

