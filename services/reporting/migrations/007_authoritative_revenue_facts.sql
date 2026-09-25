-- Staging tables for authoritative revenue dimensions. This migration is safe
-- to apply before historical backfill and does not change the active views.
CREATE TABLE IF NOT EXISTS reporting_capture_references (
  event_id text PRIMARY KEY,
  business_ref text NOT NULL,
  payment_intent_id text,
  payment_purpose text,
  source_occurred_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reporting_capture_references_business
  ON reporting_capture_references (business_ref);

CREATE TABLE IF NOT EXISTS reporting_order_routes (
  order_id text NOT NULL,
  route_id text NOT NULL,
  service_date date,
  source_occurred_at timestamptz NOT NULL,
  PRIMARY KEY (order_id, route_id)
);
CREATE TABLE IF NOT EXISTS reporting_order_travelers (
  order_id text NOT NULL,
  traveler_ref text NOT NULL,
  traveler_type text NOT NULL,
  source_occurred_at timestamptz NOT NULL,
  PRIMARY KEY (order_id, traveler_ref)
);
CREATE TABLE IF NOT EXISTS reporting_business_scopes (
  business_ref text PRIMARY KEY,
  order_id text NOT NULL,
  segment_refs text[] NOT NULL DEFAULT '{}',
  traveler_refs text[] NOT NULL DEFAULT '{}',
  source_occurred_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reporting_business_scopes_order
  ON reporting_business_scopes (order_id);

CREATE TABLE IF NOT EXISTS reporting_seat_classes (
  order_id text NOT NULL,
  segment_ref text NOT NULL,
  traveler_ref text NOT NULL,
  seat_class text NOT NULL,
  source_priority integer NOT NULL,
  source_occurred_at timestamptz NOT NULL,
  PRIMARY KEY (order_id, segment_ref, traveler_ref)
);
CREATE TABLE IF NOT EXISTS reporting_order_attachments (
  order_id text NOT NULL,
  attachment_type text NOT NULL CHECK (attachment_type IN ('ANCILLARY', 'INSURANCE')),
  source_occurred_at timestamptz NOT NULL,
  PRIMARY KEY (order_id, attachment_type)
);

CREATE TABLE IF NOT EXISTS reporting_dimension_backfills (
  backfill_name text PRIMARY KEY,
  completed_at timestamptz NOT NULL,
  details jsonb NOT NULL
);
