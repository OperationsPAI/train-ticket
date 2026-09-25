CREATE TABLE IF NOT EXISTS event_replay_archive (
  archive_seq    bigserial PRIMARY KEY,
  event_id       text NOT NULL UNIQUE,
  event_type     text NOT NULL,
  stream         text NOT NULL,
  envelope       jsonb NOT NULL,
  archived_at    timestamptz NOT NULL DEFAULT now(),
  retained_until timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS event_replay_archive_retention_idx
  ON event_replay_archive (retained_until, archive_seq);

CREATE INDEX IF NOT EXISTS event_replay_archive_type_seq_idx
  ON event_replay_archive (event_type, archive_seq);

CREATE TABLE IF NOT EXISTS event_replay_retention_state (
  singleton             boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  discarded_through_seq bigint NOT NULL,
  updated_at            timestamptz NOT NULL DEFAULT now()
);
