CREATE TABLE IF NOT EXISTS invoicing_projection_rows (
  kind text NOT NULL,
  id text NOT NULL,
  data jsonb NOT NULL,
  PRIMARY KEY (kind, id)
);

INSERT INTO invoicing_projection_rows (kind, id, data)
SELECT section.key,
       CASE WHEN section.key = 'saga_invoices' THEN entry.value->>'sagaId' ELSE entry.key END,
       entry.value
FROM invoicing_state s,
     LATERAL jsonb_each(s.data) section,
     LATERAL jsonb_each(section.value) entry
WHERE s.id = 1 AND section.key IN ('orders', 'amounts', 'saga_invoices')
ON CONFLICT DO NOTHING;

UPDATE invoicing_state SET data = data || '{"orders":{},"amounts":{},"saga_invoices":{}}'::jsonb
WHERE id = 1;
