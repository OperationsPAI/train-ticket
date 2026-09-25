-- Exact operational windows replace the former latest-10,000-row sample.
-- On populated installations create these indexes concurrently with
-- scripts/prepare_operational_indexes.py before service startup.
DO $$
BEGIN
  IF EXISTS (SELECT event_id FROM reporting_metric_events ORDER BY event_id LIMIT 1)
     AND (to_regclass('idx_reporting_metric_events_type_occurred') IS NULL
       OR to_regclass('idx_reporting_metric_events_latency_occurred') IS NULL
       OR to_regclass('idx_reporting_metric_events_capacity_latest') IS NULL) THEN
    RAISE EXCEPTION 'Migration 009 requires concurrent operational indexes on populated reporting data';
  END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_reporting_metric_events_type_occurred
  ON reporting_metric_events (event_type, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_reporting_metric_events_latency_occurred
  ON reporting_metric_events (occurred_at DESC, booking_latency_ms)
  WHERE booking_latency_ms IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_reporting_metric_events_capacity_latest
  ON reporting_metric_events (route_id, service_date, occurred_at DESC, event_id DESC)
  INCLUDE (capacity, confirmed)
  WHERE route_id IS NOT NULL AND service_date IS NOT NULL
    AND (capacity IS NOT NULL OR confirmed IS NOT NULL);
