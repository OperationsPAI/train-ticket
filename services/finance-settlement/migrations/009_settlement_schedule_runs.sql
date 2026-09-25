CREATE TABLE IF NOT EXISTS settlement_schedule_runs (
  job_key       text PRIMARY KEY,
  job_type      text NOT NULL,
  start_date    date NOT NULL,
  end_date      date NOT NULL,
  status        text NOT NULL,
  lease_owner   text NOT NULL,
  lease_until   timestamptz NOT NULL,
  attempts      integer NOT NULL,
  result_refs   jsonb NOT NULL DEFAULT '[]'::jsonb,
  failure       text,
  started_at    timestamptz NOT NULL,
  completed_at  timestamptz,
  updated_at    timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_settlement_schedule_runs_updated
  ON settlement_schedule_runs (updated_at DESC);
