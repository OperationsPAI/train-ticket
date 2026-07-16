CREATE TABLE IF NOT EXISTS corporate_agreements (
    agreement_id TEXT PRIMARY KEY,
    corporate_id TEXT NOT NULL,
    agreement_code TEXT NOT NULL,
    legal_name TEXT NOT NULL,
    agreement_version INTEGER NOT NULL CHECK (agreement_version > 0),
    status TEXT NOT NULL,
    effective_starts_at TIMESTAMPTZ NOT NULL,
    effective_ends_at TIMESTAMPTZ NOT NULL,
    monthly_credit_currency CHAR(3) NOT NULL,
    monthly_credit_minor_units BIGINT NOT NULL CHECK (monthly_credit_minor_units >= 0),
    agreement_price_ref_digest TEXT NOT NULL,
    document JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ,
    CONSTRAINT corporate_agreement_effective_window CHECK (effective_ends_at > effective_starts_at)
);

CREATE INDEX IF NOT EXISTS idx_corporate_agreements_corporate_id
    ON corporate_agreements (corporate_id, agreement_code, status);

CREATE TABLE IF NOT EXISTS authorized_travelers (
    authorization_id TEXT PRIMARY KEY,
    agreement_id TEXT NOT NULL REFERENCES corporate_agreements (agreement_id),
    corporate_id TEXT NOT NULL,
    account_id TEXT NOT NULL,
    cost_center TEXT NOT NULL,
    status TEXT NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    document JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT authorized_traveler_valid_window CHECK (valid_until > valid_from)
);

CREATE INDEX IF NOT EXISTS idx_authorized_travelers_agreement_account
    ON authorized_travelers (agreement_id, account_id, status);

CREATE TABLE IF NOT EXISTS corporate_billing_periods (
    statement_id TEXT PRIMARY KEY,
    corporate_id TEXT NOT NULL,
    agreement_id TEXT NOT NULL REFERENCES corporate_agreements (agreement_id),
    billing_period TEXT NOT NULL,
    currency CHAR(3) NOT NULL,
    status TEXT NOT NULL,
    statement_hash TEXT,
    cutoff_at TIMESTAMPTZ NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    document JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT corporate_billing_due_after_cutoff CHECK (due_at > cutoff_at),
    CONSTRAINT corporate_billing_unique_period UNIQUE (corporate_id, agreement_id, billing_period)
);

CREATE TABLE IF NOT EXISTS outbox_events (
    event_id TEXT PRIMARY KEY,
    producer TEXT NOT NULL,
    event_type TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS travel_policies (
    agreement_id TEXT PRIMARY KEY REFERENCES corporate_agreements (agreement_id),
    document JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS approval_requests (
    request_id TEXT PRIMARY KEY,
    booking_ref TEXT NOT NULL,
    employee_ref TEXT NOT NULL,
    current_level INTEGER NOT NULL CHECK (current_level BETWEEN 1 AND 3),
    status TEXT NOT NULL,
    document JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_approval_requests_booking
    ON approval_requests (booking_ref, status);

CREATE TABLE IF NOT EXISTS budget_pools (
    pool_id TEXT PRIMARY KEY,
    dimension TEXT NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    limit_minor BIGINT NOT NULL CHECK (limit_minor >= 0),
    reserved_minor BIGINT NOT NULL CHECK (reserved_minor >= 0),
    committed_minor BIGINT NOT NULL CHECK (committed_minor >= 0),
    document JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT budget_period_window CHECK (period_end > period_start)
);

CREATE INDEX IF NOT EXISTS idx_budget_pools_dimension_period
    ON budget_pools (dimension, period_start, period_end);

CREATE TABLE IF NOT EXISTS budget_reservations (
    reservation_id TEXT PRIMARY KEY,
    pool_id TEXT NOT NULL REFERENCES budget_pools (pool_id),
    booking_ref TEXT NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor >= 0),
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_budget_reservations_booking
    ON budget_reservations (booking_ref, status);

CREATE TABLE IF NOT EXISTS monthly_invoices (
    invoice_id TEXT PRIMARY KEY,
    agreement_id TEXT NOT NULL REFERENCES corporate_agreements (agreement_id),
    billing_period TEXT NOT NULL,
    total_minor BIGINT NOT NULL CHECK (total_minor >= 0),
    discount_minor BIGINT NOT NULL CHECK (discount_minor >= 0),
    document JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);


CREATE TABLE IF NOT EXISTS processed_events (
    event_id TEXT PRIMARY KEY,
    stream TEXT,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS outbox (
    seq BIGSERIAL PRIMARY KEY,
    event_id TEXT NOT NULL UNIQUE,
    stream TEXT NOT NULL,
    envelope JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished_seq
    ON outbox (seq) WHERE published_at IS NULL;
