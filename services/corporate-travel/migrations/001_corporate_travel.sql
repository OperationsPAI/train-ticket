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

CREATE TABLE IF NOT EXISTS corporate_travel_inbox (
    event_id TEXT PRIMARY KEY,
    event_type TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS outbox_events (
    event_id TEXT PRIMARY KEY,
    producer TEXT NOT NULL,
    event_type TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    published_at TIMESTAMPTZ
);
