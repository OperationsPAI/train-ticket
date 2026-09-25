-- Covering index for the candidate search.
--
-- The search resolves an origin reference to every node that shares its place
-- and then, for each of them, reads the segments departing on the requested
-- date inside a time window. The existing index carries
-- (departure_date, origin_stop_ref, destination_stop_ref), so each of those
-- lookups still visits the heap for the columns the query selects, and a search
-- measured 1366 milliseconds against the resident table of 740870 segments --
-- at thirty searches a second that is a whole core of the database per search
-- path, and under the load generator's peak Trip Planning answered 14425
-- searches with `Itinerary storage unavailable`.
--
-- With departure_time in the key and the selected columns included, the same
-- search measures 130 to 210 milliseconds.
--
-- On a database that already holds plan segments, create it ONLINE before this
-- migration runs:
--
--   CREATE INDEX CONCURRENTLY idx_plan_segments_search_covering
--     ON plan_segments (departure_date, origin_stop_ref, departure_time)
--     INCLUDE (segment_ref, scheduled_service_ref, destination_stop_ref);
--
-- The statement below is the IF NOT EXISTS form, so a database that already has
-- it applies this migration as a no-op rather than blocking searches behind a
-- transaction that cannot use CONCURRENTLY.
CREATE INDEX IF NOT EXISTS idx_plan_segments_search_covering
  ON plan_segments (departure_date, origin_stop_ref, departure_time)
  INCLUDE (segment_ref, scheduled_service_ref, destination_stop_ref);
