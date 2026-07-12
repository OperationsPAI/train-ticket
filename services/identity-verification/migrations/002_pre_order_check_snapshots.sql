CREATE TABLE IF NOT EXISTS pre_order_check_snapshots (
  id text PRIMARY KEY,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_pre_order_check_material
  ON pre_order_check_snapshots ((data->>'materialHash'));
CREATE INDEX IF NOT EXISTS idx_pre_order_check_order_intent
  ON pre_order_check_snapshots ((data->'body'->>'orderIntentId'));
