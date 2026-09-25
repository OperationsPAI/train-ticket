CREATE TABLE IF NOT EXISTS event_dead_letters (
  consumer_group         text NOT NULL,
  source_stream          text NOT NULL,
  evidence_key           text NOT NULL,
  source_message_id      text NOT NULL,
  consumer_name          text NOT NULL,
  event_id               text,
  event_type             text,
  producer               text,
  original_envelope      text,
  first_failure_kind     text NOT NULL CHECK (first_failure_kind IN ('INVALID_ENVELOPE','FATAL_HANDLER','RETRY_EXHAUSTED')),
  first_failure_reason   text NOT NULL,
  failure_kind           text NOT NULL CHECK (failure_kind IN ('INVALID_ENVELOPE','FATAL_HANDLER','RETRY_EXHAUSTED')),
  failure_reason         text NOT NULL,
  attempts               integer NOT NULL CHECK (attempts > 0),
  first_dead_lettered_at timestamptz NOT NULL,
  last_dead_lettered_at  timestamptz NOT NULL,
  recovery_status        text NOT NULL CHECK (recovery_status IN ('OPEN','REPLAYING','RECOVERED','DISMISSED')),
  recovery_source        text,
  recovery_sequence      bigint,
  recovery_started_at    timestamptz,
  recovery_finished_at   timestamptz,
  recovery_error         text,
  PRIMARY KEY (consumer_group, source_stream, evidence_key)
);

CREATE INDEX IF NOT EXISTS event_dead_letters_status_time_idx
  ON event_dead_letters (recovery_status, first_dead_lettered_at);

CREATE INDEX IF NOT EXISTS event_dead_letters_event_id_idx
  ON event_dead_letters (event_id)
  WHERE event_id IS NOT NULL;
