CREATE OR REPLACE FUNCTION duplicate_classification(data jsonb)
RETURNS text
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT COALESCE(NULLIF(btrim(data->>'classification'), ''), '__UNCLASSIFIED__')
$$;

CREATE OR REPLACE FUNCTION duplicate_business_ref(data jsonb)
RETURNS text
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT COALESCE(
    NULLIF(btrim(data->'businessReferences'->>'journeyOrderId'), ''),
    NULLIF(btrim(data->'businessReferences'->>'postSalesCaseId'), ''),
    NULLIF(btrim(data->'businessReferences'->>'paymentRef'), ''),
    NULLIF(btrim(data->'businessReferences'->>'recoveryCaseId'), ''),
    NULLIF(btrim(data->'businessReferences'->>'accountRef'), ''),
    '__NO_BUSINESS_REF__'
  )
$$;

CREATE TABLE IF NOT EXISTS support_case_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS support_case_snapshots_requester_idx
  ON support_case_snapshots ((data->>'requesterRef'), updated_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS support_case_snapshots_business_duplicate_idx
  ON support_case_snapshots ((data->>'requesterRef'), duplicate_classification(data), duplicate_business_ref(data));

CREATE TABLE IF NOT EXISTS case_timelines (
  case_id    text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS case_evidence_refs (
  evidence_id text PRIMARY KEY,
  case_id     text NOT NULL,
  data        jsonb NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS case_evidence_refs_case_idx
  ON case_evidence_refs (case_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS manual_action_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS manual_action_snapshots_case_idx
  ON manual_action_snapshots ((data->>'caseId'), updated_at DESC);

CREATE TABLE IF NOT EXISTS outbox (
  seq          bigserial PRIMARY KEY,
  event_id     text NOT NULL UNIQUE,
  stream       text NOT NULL,
  envelope     jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

CREATE INDEX IF NOT EXISTS outbox_unpublished_seq_idx
  ON outbox (seq)
  WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS processed_events (
  event_id     text PRIMARY KEY,
  stream       text,
  processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS idempotency_records (
  key           text PRIMARY KEY,
  request_hash  text NOT NULL,
  status_code   int NOT NULL,
  response_body jsonb,
  created_at    timestamptz NOT NULL DEFAULT now()
);
