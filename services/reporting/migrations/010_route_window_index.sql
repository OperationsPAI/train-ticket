-- Cover the 24-hour route page without loading route events into Python.
-- On populated installations create this index concurrently before startup.
DO $$
BEGIN
  IF EXISTS (SELECT event_id FROM reporting_metric_events ORDER BY event_id LIMIT 1)
     AND to_regclass('idx_reporting_metric_events_route_window') IS NULL THEN
    RAISE EXCEPTION 'Migration 010 requires the concurrent route window index on populated reporting data';
  END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_reporting_metric_events_route_window
  ON reporting_metric_events (occurred_at DESC,route_id,service_date)
  INCLUDE (event_id,event_type,seat_class,capacity,confirmed)
  WHERE route_id IS NOT NULL;
