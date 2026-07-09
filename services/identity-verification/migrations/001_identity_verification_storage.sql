CREATE TABLE IF NOT EXISTS credential_record_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_credential_record_duplicate
  ON credential_record_snapshots ((data->>'travelerId'), (data->>'documentType'), (data->>'documentHash'), (data->>'materialFingerprint'), (data->>'profileSnapshotVersion'));
CREATE INDEX IF NOT EXISTS idx_credential_record_traveler
  ON credential_record_snapshots ((data->>'travelerId'), (data->>'updatedAt'));
CREATE INDEX IF NOT EXISTS idx_credential_record_document_hash
  ON credential_record_snapshots ((data->>'documentHash'));

CREATE TABLE IF NOT EXISTS verification_case_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_verification_case_duplicate
  ON verification_case_snapshots ((data->>'travelerId'), (data->>'credentialRecordId'), (data->>'purpose'), (data->>'materialFingerprint'), (data->>'simPolicyVersion'));
CREATE INDEX IF NOT EXISTS idx_verification_case_credential
  ON verification_case_snapshots ((data->>'credentialRecordId'), (data->>'createdAt'));

CREATE TABLE IF NOT EXISTS eligibility_certificate_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_eligibility_certificate_duplicate
  ON eligibility_certificate_snapshots ((data->>'travelerId'), (data->>'eligibilityType'), (data->>'certificateHash'), (data->>'policyYear'), (data->>'policyVersion'));
CREATE INDEX IF NOT EXISTS idx_eligibility_certificate_query
  ON eligibility_certificate_snapshots ((data->>'travelerId'), (data->>'eligibilityType'), (data->>'status'), (substring(data->>'validFrom' from 1 for 10)), (substring(data->>'validUntil' from 1 for 10)));
CREATE INDEX IF NOT EXISTS idx_eligibility_certificate_products
  ON eligibility_certificate_snapshots USING gin ((data->'applicableProductCodes'));

CREATE TABLE IF NOT EXISTS purchase_limit_fact_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_purchase_limit_fact_duplicate
  ON purchase_limit_fact_snapshots ((data->>'scopeType'), (data->>'scopeRef'), (data->>'journeyDate'), (data->>'productCode'), (data->>'orderIntentId'));
CREATE INDEX IF NOT EXISTS idx_purchase_limit_fact_order_intent
  ON purchase_limit_fact_snapshots ((data->>'orderIntentId'));

CREATE TABLE IF NOT EXISTS traveler_snapshot_links (
  traveler_id text PRIMARY KEY,
  snapshot_version text NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

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
