CREATE INDEX IF NOT EXISTS idx_mct_published_lookup
ON mct_rule_snapshots ((data->>'fromNodeType'), (data->>'toNodeType'), (data->>'transferCategory'), version DESC, id DESC)
WHERE data->>'status' = 'PUBLISHED';
