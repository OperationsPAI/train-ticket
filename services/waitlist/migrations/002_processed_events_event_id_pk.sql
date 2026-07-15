DELETE FROM processed_events duplicate
USING processed_events keeper
WHERE duplicate.event_id = keeper.event_id
  AND (duplicate.processed_at > keeper.processed_at
    OR (duplicate.processed_at = keeper.processed_at AND duplicate.ctid > keeper.ctid));

ALTER TABLE processed_events DROP CONSTRAINT IF EXISTS processed_events_pkey;
ALTER TABLE processed_events ALTER COLUMN event_id SET NOT NULL;
ALTER TABLE processed_events ALTER COLUMN stream DROP NOT NULL;
ALTER TABLE processed_events ADD PRIMARY KEY (event_id);
