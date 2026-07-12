CREATE TABLE IF NOT EXISTS compensation_offer_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS compensation_offer_snapshots_ticket_idx
  ON compensation_offer_snapshots ((data->>'ticketId'), updated_at DESC);

CREATE INDEX IF NOT EXISTS support_case_snapshots_escalation_level_idx
  ON support_case_snapshots ((data->>'escalationLevel'), updated_at DESC);
