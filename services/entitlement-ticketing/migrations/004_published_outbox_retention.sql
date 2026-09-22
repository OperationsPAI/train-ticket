CREATE INDEX IF NOT EXISTS idx_outbox_published_retention
    ON outbox (published_at) WHERE published_at IS NOT NULL;
