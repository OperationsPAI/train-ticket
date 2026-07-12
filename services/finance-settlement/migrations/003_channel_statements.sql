CREATE TABLE IF NOT EXISTS channel_statements (
    channel_statement_id TEXT PRIMARY KEY,
    channel TEXT NOT NULL,
    statement_date TEXT NOT NULL,
    currency TEXT NOT NULL,
    seed_version TEXT NOT NULL DEFAULT '',
    line_count INT NOT NULL DEFAULT 0,
    gross_payment_currency TEXT NOT NULL DEFAULT 'CNY',
    gross_payment_amount NUMERIC NOT NULL DEFAULT 0,
    gross_refund_currency TEXT NOT NULL DEFAULT 'CNY',
    gross_refund_amount NUMERIC NOT NULL DEFAULT 0,
    fee_currency TEXT NOT NULL DEFAULT 'CNY',
    fee_amount NUMERIC NOT NULL DEFAULT 0,
    statement_hash TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'GENERATED',
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    frozen_at TIMESTAMPTZ,
    source_event_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_channel_statements_channel_date ON channel_statements (channel, statement_date);
