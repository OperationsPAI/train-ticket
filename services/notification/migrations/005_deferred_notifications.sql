CREATE TABLE notification_deferred (
  event_id text PRIMARY KEY,
  stream text,
  envelope jsonb NOT NULL,
  retry_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notification_deferred_due_idx ON notification_deferred(retry_at);

CREATE TABLE notification_aggregation (
  recipient_ref text NOT NULL,
  order_ref text NOT NULL,
  template_type text NOT NULL,
  occurred_at timestamptz NOT NULL,
  PRIMARY KEY (recipient_ref, order_ref)
);
