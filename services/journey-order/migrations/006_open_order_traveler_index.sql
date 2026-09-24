-- Restrict the traveler-containment index to orders that are still open.
--
-- `idx_jos_travelers_gin` made the containment lookup cheap when the table held
-- 58,156 snapshots. It now holds 6,805,783 snapshots and one traveler carries
-- 531 historical orders of which only 2 are open, so every identity event still
-- fetched all 531 rows from the heap and discarded the closed ones. The three
-- identity handlers run once per verification event, and a burst produces five
-- hundred to seven hundred of them per second, so the discarded rows were the
-- largest single reader on the database.
--
-- The predicate matches the handler filter exactly, which is what lets the
-- planner choose this index: `data->>'state' NOT IN ('CANCELLED', 'COMPLETED',
-- 'FAILED')`. Rows without a state stay out of the index for the same reason
-- they stay out of the query result.
CREATE INDEX IF NOT EXISTS idx_jos_travelers_open_gin
    ON journey_order_snapshots USING gin ((data->'travelers') jsonb_path_ops)
    WHERE data->>'state' NOT IN ('CANCELLED', 'COMPLETED', 'FAILED');

-- The unrestricted index no longer serves any query: this method is its only
-- reader, and its own predicate admits everything the handler asks for. Left in
-- place the planner keeps choosing it, because the containment estimate is the
-- same for both indexes while the unrestricted one covers more entries.
DROP INDEX IF EXISTS idx_jos_travelers_gin;
