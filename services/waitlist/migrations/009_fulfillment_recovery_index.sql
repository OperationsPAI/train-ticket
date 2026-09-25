CREATE INDEX IF NOT EXISTS waitlist_fulfillment_recovery_idx
    ON waitlist_entries (updated_at, entry_id)
    WHERE status = 'MATCHING' AND data->>'journeyOrderRef' IS NOT NULL;
