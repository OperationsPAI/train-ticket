CREATE TABLE IF NOT EXISTS journey_order_identity_checks (
  order_id text PRIMARY KEY,
  pre_order_check_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
