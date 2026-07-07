UPDATE traveler_profile_snapshots
SET data = jsonb_set(
    data,
    '{documents}',
    COALESCE((
        SELECT jsonb_agg(
            document - 'documentNumber'
            || jsonb_build_object(
                'maskedDocumentRef',
                CASE
                    WHEN length(document->>'documentNumber') <= 4 THEN '***' || (document->>'documentNumber')
                    ELSE substring(document->>'documentNumber' FROM 1 FOR 2)
                        || '***'
                        || right(document->>'documentNumber', 4)
                END,
                'documentNumberHash', 'scrubbed:' || md5(document->>'documentNumber')
            )
        )
        FROM jsonb_array_elements(data->'documents') AS document
    ), '[]'::jsonb)
)
WHERE data->'documents' IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM jsonb_array_elements(data->'documents') AS document
      WHERE document ? 'documentNumber'
  );
