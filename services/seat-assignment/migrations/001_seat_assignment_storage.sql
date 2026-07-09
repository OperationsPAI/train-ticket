CREATE TABLE IF NOT EXISTS seat_maps (
  seat_map_id TEXT PRIMARY KEY,
  scheduled_service_ref TEXT NOT NULL,
  service_date TEXT NOT NULL,
  status TEXT NOT NULL,
  version BIGINT NOT NULL,
  data JSONB NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (to_char(now() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'))
);
CREATE INDEX IF NOT EXISTS idx_seat_maps_service_date ON seat_maps (scheduled_service_ref, service_date, status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_seat_maps_published ON seat_maps (scheduled_service_ref, service_date) WHERE status = 'PUBLISHED';

CREATE TABLE IF NOT EXISTS seat_allocations (
  seat_allocation_id TEXT PRIMARY KEY,
  segment_booking_id TEXT NOT NULL,
  capacity_hold_id TEXT NOT NULL,
  seat_map_id TEXT,
  seat_unit_ref TEXT,
  status TEXT NOT NULL,
  data JSONB NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (to_char(now() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'))
);
CREATE INDEX IF NOT EXISTS idx_seat_allocations_segment ON seat_allocations (segment_booking_id, status);
CREATE INDEX IF NOT EXISTS idx_seat_allocations_hold ON seat_allocations (capacity_hold_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_seat_allocations_active_booking ON seat_allocations (segment_booking_id) WHERE status IN ('ALLOCATED','STANDING','CONFIRMED');

CREATE TABLE IF NOT EXISTS seat_assignment_processed_events (
  event_id TEXT PRIMARY KEY,
  stream TEXT NOT NULL,
  processed_at TEXT NOT NULL DEFAULT (to_char(now() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'))
);
