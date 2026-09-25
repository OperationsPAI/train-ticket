-- Exact delivery queue depth, maintained by the database rather than by the
-- service.
--
-- The queue depth is the one number a backlog alert needs, and it cannot be
-- read with a count: `notification_delivery_queue` holds every task the service
-- ever accepted, so a group-by-state count over it is a sequential scan of tens
-- of millions of rows. Measured on the resident database while the policy drain
-- was running: 3.7 seconds for `count(*) WHERE state='Ready'` as a parallel
-- sequential scan, and 27.8 seconds for the same query forced onto the ready
-- index, because that index is not covered by the visibility map after the mass
-- updates the drain performs.
--
-- A trigger maintains the counts instead. It cannot be missed by a new code
-- path the way a hand-written counter bump can, and the write cost is one small
-- single-row upsert inside the transaction that already moved the task, so it
-- scales with the transition rate rather than with the table size.
CREATE TABLE IF NOT EXISTS notification_delivery_queue_state (
  state text NOT NULL,
  channel text NOT NULL,
  tasks bigint NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (state, channel)
);

-- Backfill before the trigger exists, so the initial population is counted once
-- instead of being double-counted by the trigger alongside a manual seed.
INSERT INTO notification_delivery_queue_state(state, channel, tasks, updated_at)
SELECT state,
       COALESCE(channels->channel_index->>'channel', 'unknown'),
       count(*),
       now()
  FROM notification_delivery_queue
 GROUP BY 1, 2
ON CONFLICT (state, channel) DO UPDATE
  SET tasks = EXCLUDED.tasks, updated_at = now();

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
       AND channel = COALESCE(OLD.channels->OLD.channel_index->>'channel', 'unknown');
  END IF;

  IF TG_OP IN ('INSERT', 'UPDATE') AND (TG_OP = 'INSERT' OR moved) THEN
    INSERT INTO notification_delivery_queue_state(state, channel, tasks, updated_at)
    VALUES (NEW.state, COALESCE(NEW.channels->NEW.channel_index->>'channel', 'unknown'), 1, now())
    ON CONFLICT (state, channel) DO UPDATE
      SET tasks = notification_delivery_queue_state.tasks + 1, updated_at = now();
  END IF;

  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS notification_delivery_queue_state_counts ON notification_delivery_queue;
CREATE TRIGGER notification_delivery_queue_state_counts
AFTER INSERT OR UPDATE OR DELETE ON notification_delivery_queue
FOR EACH ROW EXECUTE FUNCTION notification_delivery_queue_state_delta();
