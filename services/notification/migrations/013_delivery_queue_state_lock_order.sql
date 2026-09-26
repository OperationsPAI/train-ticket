-- The counter update takes its two rows in a canonical order.
--
-- A queue row that changes state touches two `notification_delivery_queue_state`
-- rows: the one it left and the one it entered. The trigger below decremented the
-- old row and then incremented the new one, so a claim (`Ready` -> `Claimed`) and
-- the lease recovery (`Claimed` -> `Ready`) took the same two rows in opposite
-- orders and deadlocked each other. Measured after the split moved notification
-- onto the event shard: `ERROR: deadlock detected` every second or two, 66 in
-- three minutes, each one logged by the delivery worker as `WORKER_CYCLE_FAILED`,
-- and `/readyz` answering 503 while the deadlocked transactions held pool
-- connections.
--
-- Both transitions now update the row whose (state, channel) sorts first before
-- the row that sorts later, so two transactions that touch the same pair cannot
-- form a cycle. The counts themselves are unchanged.
CREATE OR REPLACE FUNCTION notification_delivery_queue_state_delta() RETURNS trigger AS $$
DECLARE
  moved boolean;
  previous_state text;
  previous_channel text;
  next_state text;
  next_channel text;
  bucket_index smallint;
BEGIN
  IF TG_OP = 'DELETE' THEN
    UPDATE notification_delivery_queue_state
       SET tasks = tasks - 1, updated_at = now()
     WHERE state = OLD.state
       AND channel = COALESCE(OLD.channels->OLD.channel_index->>'channel', 'unknown')
       AND bucket = (hashtextextended(OLD.task_id, 0) & 15)::smallint;
    RETURN NULL;
  END IF;

  IF TG_OP = 'INSERT' THEN
    INSERT INTO notification_delivery_queue_state(state, channel, bucket, tasks, updated_at)
    VALUES (NEW.state,
            COALESCE(NEW.channels->NEW.channel_index->>'channel', 'unknown'),
            (hashtextextended(NEW.task_id, 0) & 15)::smallint,
            1,
            now())
    ON CONFLICT (state, channel, bucket) DO UPDATE
      SET tasks = notification_delivery_queue_state.tasks + 1, updated_at = now();
    RETURN NULL;
  END IF;

  -- A transition that kept both the state and the channel (a retry with a new
  -- available_at, a refreshed lease, a bumped attempt counter) does not move the
  -- row between buckets, so it does not touch the counts at all.
  moved := NEW.state IS DISTINCT FROM OLD.state
    OR COALESCE(NEW.channels->NEW.channel_index->>'channel', 'unknown')
       IS DISTINCT FROM COALESCE(OLD.channels->OLD.channel_index->>'channel', 'unknown');
  IF NOT moved THEN
    RETURN NULL;
  END IF;

  previous_state := OLD.state;
  previous_channel := COALESCE(OLD.channels->OLD.channel_index->>'channel', 'unknown');
  next_state := NEW.state;
  next_channel := COALESCE(NEW.channels->NEW.channel_index->>'channel', 'unknown');
  bucket_index := (hashtextextended(OLD.task_id, 0) & 15)::smallint;

  IF (previous_state, previous_channel) < (next_state, next_channel) THEN
    UPDATE notification_delivery_queue_state
       SET tasks = tasks - 1, updated_at = now()
     WHERE state = previous_state AND channel = previous_channel AND bucket = bucket_index;
    INSERT INTO notification_delivery_queue_state(state, channel, bucket, tasks, updated_at)
    VALUES (next_state, next_channel, bucket_index, 1, now())
    ON CONFLICT (state, channel, bucket) DO UPDATE
      SET tasks = notification_delivery_queue_state.tasks + 1, updated_at = now();
  ELSE
    INSERT INTO notification_delivery_queue_state(state, channel, bucket, tasks, updated_at)
    VALUES (next_state, next_channel, bucket_index, 1, now())
    ON CONFLICT (state, channel, bucket) DO UPDATE
      SET tasks = notification_delivery_queue_state.tasks + 1, updated_at = now();
    UPDATE notification_delivery_queue_state
       SET tasks = tasks - 1, updated_at = now()
     WHERE state = previous_state AND channel = previous_channel AND bucket = bucket_index;
  END IF;

  RETURN NULL;
END;
$$ LANGUAGE plpgsql;
