CREATE TABLE IF NOT EXISTS event_replay_checkpoints (
  consumer_group  text NOT NULL,
  source_name     text NOT NULL,
  source_endpoint text NOT NULL,
  after_sequence  bigint NOT NULL DEFAULT 0 CHECK (after_sequence >= 0),
  status          text NOT NULL CHECK (status IN ('ACTIVE','ERROR','GAP')),
  last_event_id   text,
  last_attempt_at timestamptz,
  last_success_at timestamptz,
  last_error      text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer_group, source_name)
);
