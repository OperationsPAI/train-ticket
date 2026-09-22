CREATE TABLE IF NOT EXISTS notification_recipient_refs (
  kind text NOT NULL,
  id text NOT NULL,
  recipient_ref text NOT NULL,
  PRIMARY KEY (kind, id)
);
