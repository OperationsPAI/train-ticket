-- The outbox retention sweep deletes published rows older than thirty seconds,
-- and it had no index for that predicate: both existing outbox indexes cover
-- `published_at IS NULL`. Every sweep therefore scanned the table, hit its
-- batch budget, and the table reached 36 million rows and 34GB while the
-- publisher wrote 212 rows per second.
CREATE INDEX IF NOT EXISTS idx_outbox_published_at
    ON outbox (published_at)
    WHERE published_at IS NOT NULL;
