CREATE TABLE IF NOT EXISTS case_context (
  context_id      text PRIMARY KEY,
  case_id         text NOT NULL,
  source_event_id text NOT NULL,
  source          text NOT NULL,
  context_type    text NOT NULL,
  severity        text NOT NULL,
  occurred_at     timestamptz NOT NULL,
  data            jsonb NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (case_id, source_event_id)
);

CREATE INDEX IF NOT EXISTS case_context_case_idx
  ON case_context (case_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS case_context_source_event_idx
  ON case_context (source_event_id);
