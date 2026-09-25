-- Delivery policy can terminate stale or superseded notifications without
-- pretending that they were sent or that the provider failed. NOT VALID avoids
-- a table-wide validation scan during the live upgrade; PostgreSQL still
-- enforces the new constraint for every new or updated row.
ALTER TABLE notification_delivery_queue
  DROP CONSTRAINT IF EXISTS notification_delivery_queue_state_check;
ALTER TABLE notification_delivery_queue
  ADD CONSTRAINT notification_delivery_queue_state_check
  CHECK (state IN ('Ready','Claimed','Sending','Unknown','Delivered','Failed','Cancelled'))
  NOT VALID;
