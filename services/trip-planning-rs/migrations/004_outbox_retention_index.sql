CREATE INDEX IF NOT EXISTS idx_outbox_published_at ON outbox (published_at) WHERE published_at IS NOT NULL;
