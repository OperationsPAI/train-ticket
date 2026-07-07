-- 001 shipped with snapshot DTOs that Jackson-wrapped the aggregate as
-- {"data": {...}}; unwrap any such rows so raw JSONB queries (businessRef
-- lookup) see the aggregate fields at the top level.
UPDATE payment_intent_snapshots
SET data = data->'data'
WHERE data ? 'data'
  AND jsonb_typeof(data->'data') = 'object'
  AND (SELECT count(*) FROM jsonb_object_keys(data)) = 1;

UPDATE refund_snapshots
SET data = data->'data'
WHERE data ? 'data'
  AND jsonb_typeof(data->'data') = 'object'
  AND (SELECT count(*) FROM jsonb_object_keys(data)) = 1;
