CREATE TABLE IF NOT EXISTS invoice_titles (
  title_id text PRIMARY KEY,
  account_id text NOT NULL,
  status text NOT NULL,
  title_type text NOT NULL,
  material_hash text NOT NULL,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_invoice_titles_active_material ON invoice_titles(account_id, material_hash) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_invoice_titles_account_status ON invoice_titles(account_id, status);

CREATE TABLE IF NOT EXISTS e_invoice_requests (
  invoice_request_id text PRIMARY KEY,
  order_id text NOT NULL,
  account_id text NOT NULL,
  title_id text NOT NULL,
  status text NOT NULL,
  material_hash text NOT NULL UNIQUE,
  e_invoice_id text,
  data jsonb NOT NULL,
  version bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_e_invoice_requests_order ON e_invoice_requests(order_id);
CREATE INDEX IF NOT EXISTS idx_e_invoice_requests_title ON e_invoice_requests(title_id);

CREATE TABLE IF NOT EXISTS e_invoices (
  e_invoice_id text PRIMARY KEY,
  invoice_request_id text NOT NULL,
  order_id text NOT NULL,
  invoice_type text NOT NULL,
  status text NOT NULL,
  data jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_e_invoices_order ON e_invoices(order_id, invoice_type, status);

CREATE TABLE IF NOT EXISTS red_flushes (
  red_flush_id text PRIMARY KEY,
  original_invoice_id text NOT NULL,
  post_sales_case_id text NOT NULL,
  order_id text NOT NULL,
  status text NOT NULL,
  data jsonb NOT NULL,
  version bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_red_flush_unique_case_invoice ON red_flushes(original_invoice_id, post_sales_case_id);
CREATE INDEX IF NOT EXISTS idx_red_flushes_order_case ON red_flushes(order_id, post_sales_case_id, status);

CREATE TABLE IF NOT EXISTS invoice_eligibility_projection (
  order_id text PRIMARY KEY,
  account_id text NOT NULL,
  status text NOT NULL,
  traveler_refs jsonb NOT NULL DEFAULT '[]'::jsonb,
  segment_refs jsonb NOT NULL DEFAULT '[]'::jsonb,
  monetary_summary jsonb,
  source_event_id text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS amount_basis_projection (
  order_id text PRIMARY KEY,
  amount_basis jsonb NOT NULL,
  source_event_id text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
