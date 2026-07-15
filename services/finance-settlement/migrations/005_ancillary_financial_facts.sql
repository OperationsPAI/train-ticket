CREATE TABLE IF NOT EXISTS ancillary_financial_facts (
  event_id                 text PRIMARY KEY,
  event_type               text NOT NULL,
  fact_kind                text NOT NULL,
  ancillary_order_item_id  text NOT NULL,
  journey_order_id         text NOT NULL,
  service_type             text NOT NULL,
  supplier_ref             text,
  payable_currency         text,
  payable_amount           numeric(19, 4),
  refundable_currency      text,
  refundable_amount        numeric(19, 4),
  refunded_currency        text,
  refunded_amount          numeric(19, 4),
  retained_currency        text,
  retained_amount          numeric(19, 4),
  occurred_at              timestamptz NOT NULL,
  updated_at               timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ancillary_financial_facts_journey_order
  ON ancillary_financial_facts (journey_order_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_ancillary_financial_facts_item
  ON ancillary_financial_facts (ancillary_order_item_id, occurred_at DESC);
