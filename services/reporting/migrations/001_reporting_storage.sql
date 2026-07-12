CREATE TABLE IF NOT EXISTS metric_definition_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_metric_definition_snapshots_category_id ON metric_definition_snapshots ((data->>'category'), id);
CREATE TABLE IF NOT EXISTS dashboard_read_model_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS consumed_event_log_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS reporting_rebuild_runs (
  rebuild_id text PRIMARY KEY,
  dashboard_id text NOT NULL,
  rebuilt_at timestamptz NOT NULL,
  status text NOT NULL,
  event_count bigint NOT NULL,
  digest text NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reporting_rebuild_runs_dashboard_rebuilt ON reporting_rebuild_runs (dashboard_id, rebuilt_at DESC);
CREATE TABLE IF NOT EXISTS outbox (
  seq bigserial PRIMARY KEY,
  event_id text NOT NULL UNIQUE,
  stream text NOT NULL,
  envelope jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished_seq ON outbox(seq) WHERE published_at IS NULL;
CREATE TABLE IF NOT EXISTS processed_events (
  event_id text PRIMARY KEY,
  stream text,
  processed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS idempotency_records (
  key text PRIMARY KEY,
  request_hash text NOT NULL,
  status_code int NOT NULL,
  response_body jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS reporting_metric_events (
  event_id text PRIMARY KEY,
  event_type text NOT NULL,
  occurred_at timestamptz NOT NULL,
  route_id text,
  service_date date,
  seat_class text,
  amount numeric(18,2),
  currency char(3),
  channel text,
  passenger_type text,
  capacity integer,
  confirmed integer,
  booking_latency_ms integer,
  flags jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_reporting_metric_events_occurred ON reporting_metric_events (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_reporting_metric_events_route_service_date ON reporting_metric_events (route_id, service_date);

CREATE TABLE IF NOT EXISTS reporting_anomalies (
  rule_id text PRIMARY KEY,
  current_value numeric(18,4) NOT NULL,
  threshold numeric(18,4) NOT NULL,
  severity text NOT NULL,
  detected_at timestamptz NOT NULL,
  resolved_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_reporting_anomalies_active_detected ON reporting_anomalies (detected_at DESC) WHERE resolved_at IS NULL;

CREATE MATERIALIZED VIEW IF NOT EXISTS reporting_revenue_by_route AS
SELECT
  route_id,
  COALESCE(currency, 'USD') AS currency,
  SUM(COALESCE(amount, 0)) AS revenue,
  COUNT(*) AS payment_count
FROM reporting_metric_events
WHERE event_type IN ('PaymentCaptured', 'RevenueRecognized', 'PaymentSucceeded')
GROUP BY route_id, COALESCE(currency, 'USD');
CREATE UNIQUE INDEX IF NOT EXISTS idx_reporting_revenue_by_route_route_currency ON reporting_revenue_by_route (route_id, currency);

CREATE MATERIALIZED VIEW IF NOT EXISTS reporting_revenue_by_seat_class AS
SELECT
  seat_class,
  COALESCE(currency, 'USD') AS currency,
  SUM(COALESCE(amount, 0)) AS revenue,
  COUNT(*) AS payment_count
FROM reporting_metric_events
WHERE event_type IN ('PaymentCaptured', 'RevenueRecognized', 'PaymentSucceeded')
GROUP BY seat_class, COALESCE(currency, 'USD');
CREATE UNIQUE INDEX IF NOT EXISTS idx_reporting_revenue_by_seat_class_currency ON reporting_revenue_by_seat_class (seat_class, currency);

CREATE MATERIALIZED VIEW IF NOT EXISTS reporting_revenue_breakdowns AS
SELECT
  'route' AS dimension,
  COALESCE(route_id, 'unknown') AS dimension_value,
  COALESCE(currency, 'USD') AS currency,
  SUM(COALESCE(amount, 0)) AS revenue,
  COUNT(*) AS payment_count,
  SUM(COALESCE((flags->>'distanceKm')::numeric, 0)) AS distance_km,
  SUM(CASE WHEN COALESCE((flags->>'ancillaryAttached')::boolean, false) THEN 1 ELSE 0 END) AS ancillary_count,
  SUM(CASE WHEN COALESCE((flags->>'insuranceAttached')::boolean, false) THEN 1 ELSE 0 END) AS insurance_count
FROM reporting_metric_events
WHERE event_type IN ('PaymentCaptured', 'RevenueRecognized', 'PaymentSucceeded')
GROUP BY COALESCE(route_id, 'unknown'), COALESCE(currency, 'USD')
UNION ALL
SELECT
  'seat_class',
  COALESCE(seat_class, 'unknown'),
  COALESCE(currency, 'USD'),
  SUM(COALESCE(amount, 0)),
  COUNT(*),
  SUM(COALESCE((flags->>'distanceKm')::numeric, 0)),
  SUM(CASE WHEN COALESCE((flags->>'ancillaryAttached')::boolean, false) THEN 1 ELSE 0 END),
  SUM(CASE WHEN COALESCE((flags->>'insuranceAttached')::boolean, false) THEN 1 ELSE 0 END)
FROM reporting_metric_events
WHERE event_type IN ('PaymentCaptured', 'RevenueRecognized', 'PaymentSucceeded')
GROUP BY COALESCE(seat_class, 'unknown'), COALESCE(currency, 'USD')
UNION ALL
SELECT
  'channel',
  COALESCE(channel, 'unknown'),
  COALESCE(currency, 'USD'),
  SUM(COALESCE(amount, 0)),
  COUNT(*),
  SUM(COALESCE((flags->>'distanceKm')::numeric, 0)),
  SUM(CASE WHEN COALESCE((flags->>'ancillaryAttached')::boolean, false) THEN 1 ELSE 0 END),
  SUM(CASE WHEN COALESCE((flags->>'insuranceAttached')::boolean, false) THEN 1 ELSE 0 END)
FROM reporting_metric_events
WHERE event_type IN ('PaymentCaptured', 'RevenueRecognized', 'PaymentSucceeded')
GROUP BY COALESCE(channel, 'unknown'), COALESCE(currency, 'USD')
UNION ALL
SELECT
  'passenger_type',
  COALESCE(passenger_type, 'unknown'),
  COALESCE(currency, 'USD'),
  SUM(COALESCE(amount, 0)),
  COUNT(*),
  SUM(COALESCE((flags->>'distanceKm')::numeric, 0)),
  SUM(CASE WHEN COALESCE((flags->>'ancillaryAttached')::boolean, false) THEN 1 ELSE 0 END),
  SUM(CASE WHEN COALESCE((flags->>'insuranceAttached')::boolean, false) THEN 1 ELSE 0 END)
FROM reporting_metric_events
WHERE event_type IN ('PaymentCaptured', 'RevenueRecognized', 'PaymentSucceeded')
GROUP BY COALESCE(passenger_type, 'unknown'), COALESCE(currency, 'USD')
UNION ALL
SELECT
  'time_period',
  to_char(date_trunc('hour', occurred_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD"T"HH24:00:00"Z"'),
  COALESCE(currency, 'USD'),
  SUM(COALESCE(amount, 0)),
  COUNT(*),
  SUM(COALESCE((flags->>'distanceKm')::numeric, 0)),
  SUM(CASE WHEN COALESCE((flags->>'ancillaryAttached')::boolean, false) THEN 1 ELSE 0 END),
  SUM(CASE WHEN COALESCE((flags->>'insuranceAttached')::boolean, false) THEN 1 ELSE 0 END)
FROM reporting_metric_events
WHERE event_type IN ('PaymentCaptured', 'RevenueRecognized', 'PaymentSucceeded')
GROUP BY date_trunc('hour', occurred_at AT TIME ZONE 'UTC'), COALESCE(currency, 'USD');
CREATE UNIQUE INDEX IF NOT EXISTS idx_reporting_revenue_breakdowns_dimension_value_currency ON reporting_revenue_breakdowns (dimension, dimension_value, currency);
