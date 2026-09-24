-- The overdue step sweep reads only this index. booking_saga_snapshots holds millions of
-- documents and offers no index a step deadline search could use, so scanning it would read the
-- whole table on every sweep interval. A row exists while a step waits for its response, and
-- deadline_ms is epoch milliseconds so the due comparison never depends on the session time zone.
CREATE TABLE IF NOT EXISTS booking_saga_step_deadlines (
  saga_id     text   NOT NULL,
  step_key    text   NOT NULL,
  deadline_ms bigint NOT NULL,
  PRIMARY KEY (saga_id, step_key)
);

CREATE INDEX IF NOT EXISTS idx_booking_saga_step_deadlines_deadline_ms
  ON booking_saga_step_deadlines (deadline_ms);
