CREATE TABLE IF NOT EXISTS capacity_snapshot_cache (
  segment_ref text NOT NULL,
  departure_date date NOT NULL,
  total_capacity integer NOT NULL CHECK (total_capacity > 0),
  remaining_capacity integer NOT NULL CHECK (remaining_capacity >= 0),
  snapshot_version bigint NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (segment_ref, departure_date),
  CHECK (remaining_capacity <= total_capacity)
);

CREATE INDEX IF NOT EXISTS idx_capacity_snapshot_cache_departure
  ON capacity_snapshot_cache(departure_date, segment_ref);

CREATE TABLE IF NOT EXISTS eligibility_certificate_cache (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_eligibility_certificate_cache_lookup
  ON eligibility_certificate_cache ((data->>'travelerId'), (data->>'eligibilityType'), (data->>'certificateStatus'));
