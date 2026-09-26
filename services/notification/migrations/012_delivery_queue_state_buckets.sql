-- The depth counter was one row per state and channel, and every queue mutation
-- locked that row for the rest of its transaction. An enqueue moves a task into
-- the same bucket a claim moves tasks out of, so the two serialise on one row:
-- one session running the claim's `UPDATE ... SET state='Claimed'` blocked 82
-- sessions inserting into `notification_delivery_queue`, and 45 more waited on
-- the per recipient advisory lock behind one of those inserts.
--
-- Measured at revision 406 on the resident database: 144 sessions active on the
-- database, `fare-pricing`, which shares that instance, answering at a median of
-- 4.1 seconds, and the load generator's ten second timeout expiring on 20220
-- quotes. The ten minute acceptance window carried 30106 failed journeys.
--
-- The counts stay exact and the read stays cheap: they are split over buckets
-- derived from the task id, so two transactions that move different tasks rarely
-- touch the same row, and the reader sums a few dozen rows instead of reading one.
ALTER TABLE notification_delivery_queue_state
  ADD COLUMN IF NOT EXISTS bucket smallint NOT NULL DEFAULT 0;

ALTER TABLE notification_delivery_queue_state
  DROP CONSTRAINT IF EXISTS notification_delivery_queue_state_pkey;
ALTER TABLE notification_delivery_queue_state
  ADD CONSTRAINT notification_delivery_queue_state_pkey PRIMARY KEY (state, channel, bucket);

-- Rebuild from the queue, so the buckets start exact. `hashtextextended` is used
-- the same way the advisory locks use it; `& 15` is the low four bits, which is
-- a non-negative bucket for either sign of the hash.
DELETE FROM notification_delivery_queue_state;
INSERT INTO notification_delivery_queue_state(state, channel, bucket, tasks, updated_at)
SELECT state,
       COALESCE(channels->channel_index->>'channel', 'unknown'),
       (hashtextextended(task_id, 0) & 15)::smallint,
       count(*),
       now()
  FROM notification_delivery_queue
 GROUP BY 1, 2, 3;

CREATE OR REPLACE FUNCTION notification_delivery_queue_state_delta() RETURNS trigger AS $$
DECLARE
  moved boolean;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    -- A transition that kept both the state and the channel (a retry with a new
    -- available_at, a refreshed lease, a bumped attempt counter) does not move
    -- the row between buckets, so it does not touch the counts at all.
    moved := NEW.state IS DISTINCT FROM OLD.state
      OR COALESCE(NEW.channels->NEW.channel_index->>'channel', 'unknown')
         IS DISTINCT FROM COALESCE(OLD.channels->OLD.channel_index->>'channel', 'unknown');
  ELSE
    moved := true;
  END IF;

  IF TG_OP IN ('UPDATE', 'DELETE') AND (TG_OP = 'DELETE' OR moved) THEN
    UPDATE notification_delivery_queue_state
       SET tasks = tasks - 1, updated_at = now()
     WHERE state = OLD.state
       AND channel = COALESCE(OLD.channels->OLD.channel_index->>'channel', 'unknown')
       AND bucket = (hashtextextended(OLD.task_id, 0) & 15)::smallint;
  END IF;

  IF TG_OP IN ('INSERT', 'UPDATE') AND (TG_OP = 'INSERT' OR moved) THEN
    INSERT INTO notification_delivery_queue_state(state, channel, bucket, tasks, updated_at)
    VALUES (NEW.state,
            COALESCE(NEW.channels->NEW.channel_index->>'channel', 'unknown'),
            (hashtextextended(NEW.task_id, 0) & 15)::smallint,
            1,
            now())
    ON CONFLICT (state, channel, bucket) DO UPDATE
      SET tasks = notification_delivery_queue_state.tasks + 1, updated_at = now();
  END IF;

  RETURN NULL;
END;
$$ LANGUAGE plpgsql;
