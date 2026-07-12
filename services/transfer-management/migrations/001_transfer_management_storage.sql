CREATE TABLE IF NOT EXISTS transfer_plan_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_transfer_plan_itinerary ON transfer_plan_snapshots ((data->>'itineraryRef'));
CREATE TABLE IF NOT EXISTS connection_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_connection_plan ON connection_snapshots ((data->>'transferPlanId'));
CREATE INDEX IF NOT EXISTS idx_connection_journey ON connection_snapshots ((data->>'journeyOrderId'));
CREATE INDEX IF NOT EXISTS idx_connection_segments ON connection_snapshots ((data->>'previousSegmentRef'), (data->>'nextSegmentRef'));
CREATE INDEX IF NOT EXISTS idx_connection_recovery_cases ON connection_snapshots USING gin ((data->'recovery'->'caseIds'));
CREATE TABLE IF NOT EXISTS connection_contract_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_connection_contract_connection ON connection_contract_snapshots ((data->>'connectionId'));
CREATE TABLE IF NOT EXISTS mct_rule_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_mct_rule_lookup ON mct_rule_snapshots ((data->>'fromNodeType'), (data->>'toNodeType'), (data->>'transferCategory'), (data->>'status'));
CREATE TABLE IF NOT EXISTS segment_status_reports (
  id text PRIMARY KEY,
  segment_ref text NOT NULL,
  source_key text NOT NULL UNIQUE,
  data jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_segment_status_reports_segment ON segment_status_reports(segment_ref);
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
