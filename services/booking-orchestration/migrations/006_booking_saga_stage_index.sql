-- The saga stage reporter reads the in-flight sagas every few seconds to rebuild the active and
-- oldest-age levels. booking_saga_snapshots holds every saga ever run, so an unindexed scan would
-- read millions of finished documents to find the small nonterminal set. This partial index keeps
-- only the statuses the reporter groups on, and the query in
-- PostgresBookingSagaStageRepository repeats the same predicate so the planner can use it.
CREATE INDEX IF NOT EXISTS idx_booking_saga_snapshots_nonterminal_status
  ON booking_saga_snapshots ((data->>'status'))
  WHERE data->>'status' IN ('PLANNED', 'RISK_CHECKING', 'RESERVING', 'SEAT_ASSIGNING',
                            'AWAITING_PAYMENT', 'CONFIRMING', 'TICKETING', 'INVOICING',
                            'PARTIALLY_CONFIRMED', 'COMPENSATING');
