CREATE TABLE IF NOT EXISTS incident_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_incident_snapshots_merge
  ON incident_snapshots ((data->>'scheduledServiceRef'), (data->>'serviceDate'));
CREATE TABLE IF NOT EXISTS recovery_case_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_recovery_case_incident_order
  ON recovery_case_snapshots ((data->>'incidentId'), (data->>'journeyOrderId'));
CREATE INDEX IF NOT EXISTS idx_recovery_case_incident_status
  ON recovery_case_snapshots ((data->>'incidentId'), (data->>'status'), (data->>'openedAt'));
CREATE INDEX IF NOT EXISTS idx_recovery_case_post_sales_ref
  ON recovery_case_snapshots ((data->'execution'->>'externalRef'));
CREATE TABLE IF NOT EXISTS segment_order_index (
  segment_ref text NOT NULL,
  journey_order_id text NOT NULL,
  indexed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (segment_ref, journey_order_id)
);
CREATE INDEX IF NOT EXISTS idx_segment_order_index_order
  ON segment_order_index (journey_order_id);
CREATE TABLE IF NOT EXISTS service_alert_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_service_alert_snapshots_incident
  ON service_alert_snapshots ((data->>'incidentId'), (data->>'publishedAt'));
CREATE INDEX IF NOT EXISTS idx_service_alert_snapshots_order_ids
  ON service_alert_snapshots USING gin ((data->'affectedOrderIds'));
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
