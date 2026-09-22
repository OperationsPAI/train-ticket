CREATE INDEX IF NOT EXISTS offer_mct_published_idx
    ON offer_upstream_mct_rules (id) WHERE data->>'status' = 'PUBLISHED';
