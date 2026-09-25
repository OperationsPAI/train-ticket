CREATE TABLE IF NOT EXISTS post_sales_execution_leases (
  case_id text PRIMARY KEY,
  owner_id text NOT NULL,
  lease_until timestamptz NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_post_sales_execution_leases_expiry
  ON post_sales_execution_leases (lease_until);
