CREATE TABLE IF NOT EXISTS reporting_projection_refresh_requests (
  event_type text PRIMARY KEY,
  generation bigint NOT NULL DEFAULT 0,
  refreshed_generation bigint NOT NULL DEFAULT 0,
  source_event jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
