CREATE INDEX IF NOT EXISTS waitlist_precise_match_idx
    ON waitlist_entries (
        segment_ref,
        departure_date,
        seat_class,
        (data->>'scheduledServiceRef'),
        (data->>'stopSequenceVersion'),
        priority_score DESC,
        created_at ASC,
        entry_id
    )
    WHERE status = 'QUEUED'
      AND data ? 'interval';
