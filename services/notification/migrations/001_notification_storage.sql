CREATE TABLE IF NOT EXISTS notification_task_snapshots (
  id         text PRIMARY KEY,
  version    bigint NOT NULL,
  data       jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS notification_task_snapshots_recipient_in_app_idx
  ON notification_task_snapshots ((data->>'recipientRef'), updated_at DESC)
  WHERE data->>'channel' = 'IN_APP';

CREATE UNIQUE INDEX IF NOT EXISTS notification_task_snapshots_trigger_recipient_template_idx
  ON notification_task_snapshots ((data->>'triggerEventId'), (data->>'recipientRef'), (data->>'templateCode'));

CREATE TABLE IF NOT EXISTS outbox (
  seq          bigserial PRIMARY KEY,
  event_id     text NOT NULL UNIQUE,
  stream       text NOT NULL,
  envelope     jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

CREATE INDEX IF NOT EXISTS outbox_unpublished_seq_idx
  ON outbox (seq)
  WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS processed_events (
  event_id     text PRIMARY KEY,
  stream       text,
  processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS idempotency_records (
  key           text PRIMARY KEY,
  request_hash  text NOT NULL,
  status_code   int NOT NULL,
  response_body jsonb,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS user_preferences (
  recipient_ref text NOT NULL,
  intent        text NOT NULL,
  channel       text NOT NULL,
  enabled       boolean NOT NULL DEFAULT true,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (recipient_ref, intent, channel)
);

CREATE TABLE IF NOT EXISTS notification_templates (
  template_code text PRIMARY KEY,
  data          jsonb NOT NULL,
  updated_at    timestamptz NOT NULL DEFAULT now()
);
