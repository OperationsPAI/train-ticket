ALTER TABLE notification_rate_limits ADD COLUMN task_id text;
CREATE UNIQUE INDEX notification_rate_limits_task_channel_idx
  ON notification_rate_limits(task_id, channel) WHERE task_id IS NOT NULL;

CREATE TABLE notification_delivery_queue (
  task_id text PRIMARY KEY REFERENCES notification_task_snapshots(id),
  channels jsonb NOT NULL CHECK (jsonb_array_length(channels) > 0),
  channel_index integer NOT NULL DEFAULT 0 CHECK (channel_index >= 0),
  state text NOT NULL CHECK (state IN ('Ready','Claimed','Sending','Unknown','Delivered','Failed')),
  available_at timestamptz NOT NULL DEFAULT now(),
  claim_token uuid,
  lease_until timestamptz,
  attempt_number integer NOT NULL DEFAULT 0,
  message_id text NOT NULL UNIQUE,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notification_delivery_ready_idx ON notification_delivery_queue(available_at,task_id) WHERE state='Ready';
CREATE INDEX notification_delivery_lease_idx ON notification_delivery_queue(lease_until,task_id) WHERE state IN ('Claimed','Sending');
CREATE TABLE notification_delivery_attempts (
  claim_token uuid PRIMARY KEY,
  task_id text NOT NULL REFERENCES notification_delivery_queue(task_id),
  attempt_number integer NOT NULL,
  channel text NOT NULL CHECK (channel IN ('EMAIL','SMS','PUSH','IN_APP')),
  state text NOT NULL,
  claimed_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  completed_at timestamptz,
  message_id text NOT NULL,
  result jsonb,
  UNIQUE(task_id,attempt_number)
);
CREATE TABLE notification_delivery_evidence (
  seq bigserial PRIMARY KEY,
  task_id text NOT NULL REFERENCES notification_delivery_queue(task_id),
  claim_token uuid REFERENCES notification_delivery_attempts(claim_token),
  kind text NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  evidence jsonb NOT NULL
);
CREATE INDEX notification_delivery_evidence_task_idx ON notification_delivery_evidence(task_id,seq);
