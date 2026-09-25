-- Persist the semantic identity of consumed events.  event_id alone can
-- suppress a producer defect that reuses an id for different business data.
-- Existing rows remain nullable because their original envelopes are no
-- longer available; the first replay after this migration records a baseline.
ALTER TABLE processed_events
  ADD COLUMN IF NOT EXISTS event_fingerprint text;
