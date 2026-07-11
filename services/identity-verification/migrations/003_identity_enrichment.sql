CREATE TABLE IF NOT EXISTS identity_blacklist_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_identity_blacklist_document
  ON identity_blacklist_snapshots ((data->>'documentNumber'), (data->>'blacklistType'));

CREATE TABLE IF NOT EXISTS active_ticket_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_active_ticket_duplicate
  ON active_ticket_snapshots ((data->>'documentNumber'), (data->>'segmentRef'), (data->>'departureDate'))
  WHERE data->>'status' = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_active_ticket_order
  ON active_ticket_snapshots ((data->>'orderId'));

CREATE TABLE IF NOT EXISTS verification_cache_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_verification_cache_traveler_document
  ON verification_cache_snapshots ((data->>'travelerId'), (data->>'documentNumber'));
