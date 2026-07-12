CREATE TABLE IF NOT EXISTS members (
  member_id text PRIMARY KEY,
  account_id text NOT NULL UNIQUE,
  tier text NOT NULL CHECK (tier IN ('SILVER', 'GOLD', 'PLATINUM', 'DIAMOND')),
  status text NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
  redeemable_points integer NOT NULL CHECK (redeemable_points >= 0),
  tier_points integer NOT NULL CHECK (tier_points >= 0),
  lifetime_points integer NOT NULL CHECK (lifetime_points >= 0),
  snapshot jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_members_account_id ON members (account_id);
CREATE INDEX IF NOT EXISTS idx_members_tier ON members (tier);

CREATE TABLE IF NOT EXISTS order_account_refs (
  order_id text PRIMARY KEY,
  account_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS membership_years (
  member_id text NOT NULL REFERENCES members(member_id) ON DELETE CASCADE,
  membership_year integer NOT NULL,
  qualifying_points integer NOT NULL CHECK (qualifying_points >= 0),
  trip_count integer NOT NULL CHECK (trip_count >= 0),
  current_tier text NOT NULL CHECK (current_tier IN ('SILVER', 'GOLD', 'PLATINUM', 'DIAMOND')),
  evaluation_date timestamptz,
  PRIMARY KEY (member_id, membership_year)
);

CREATE TABLE IF NOT EXISTS points_ledger (
  entry_id text PRIMARY KEY,
  member_id text NOT NULL REFERENCES members(member_id) ON DELETE CASCADE,
  entry_type text NOT NULL CHECK (entry_type IN ('EARNED', 'REDEEMED', 'EXPIRED', 'ADJUSTED')),
  points integer NOT NULL,
  source_event_id text NOT NULL,
  source_event_type text NOT NULL,
  business_reason jsonb NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_points_ledger_member_source_event ON points_ledger (member_id, source_event_id);
CREATE INDEX IF NOT EXISTS idx_points_ledger_member_occurred ON points_ledger (member_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS idempotency_records (
  key text PRIMARY KEY,
  request_hash text NOT NULL,
  status_code integer NOT NULL,
  response_body jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS outbox (
  seq bigserial PRIMARY KEY,
  event_id text NOT NULL UNIQUE,
  stream text NOT NULL,
  envelope jsonb NOT NULL,
  published_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished_seq ON outbox (seq) WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS processed_events (
  event_id text PRIMARY KEY,
  stream text,
  processed_at timestamptz NOT NULL DEFAULT now()
);
