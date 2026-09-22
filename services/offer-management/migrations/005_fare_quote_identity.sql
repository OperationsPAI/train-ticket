INSERT INTO offer_upstream_fare_quotes(id, version, data, updated_at)
SELECT data->>'quoteId', version, data, updated_at
FROM offer_upstream_fare_quotes
WHERE id <> data->>'quoteId'
  AND (data->>'validUntil')::timestamptz > now()
ON CONFLICT (id) DO NOTHING;
