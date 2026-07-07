CREATE INDEX IF NOT EXISTS idx_capacity_hold_segment_booking_state
  ON capacity_hold_snapshots ((data #>> '{references,segmentBookingRef}'), (data ->> 'state'));
