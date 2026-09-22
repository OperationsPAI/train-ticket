CREATE TABLE invoicing_command_rows (
  kind text NOT NULL,
  id text NOT NULL,
  account_id text NOT NULL,
  order_id text,
  data jsonb NOT NULL,
  PRIMARY KEY (kind, id)
);
CREATE INDEX invoicing_command_account_idx ON invoicing_command_rows(account_id, kind);
CREATE INDEX invoicing_command_order_idx ON invoicing_command_rows(order_id, kind);

INSERT INTO invoicing_command_rows(kind,id,account_id,order_id,data)
SELECT section.key, entry.key,
       COALESCE(entry.value->>'accountId', entry.value->'response'->>'accountId',
           (SELECT invoice.value->>'accountId' FROM jsonb_each(COALESCE(s.data->'invoices','{}')) invoice
            WHERE invoice.value->>'orderId'=entry.value->>'orderId' LIMIT 1), ''),
       COALESCE(entry.value->>'orderId',entry.value->'response'->>'orderId'), entry.value
FROM invoicing_state s,
     LATERAL jsonb_each(s.data) section,
     LATERAL jsonb_each(section.value) entry
WHERE section.key IN ('titles','requests','invoices','red_flushes','idempotency')
ON CONFLICT DO NOTHING;

UPDATE invoicing_state SET data='{}'::jsonb;
