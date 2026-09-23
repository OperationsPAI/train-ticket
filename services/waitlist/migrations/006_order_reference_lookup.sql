CREATE INDEX IF NOT EXISTS waitlist_journey_order_ref_idx
    ON waitlist_entries ((data->>'journeyOrderRef'), created_at, entry_id)
    WHERE data->>'journeyOrderRef' IS NOT NULL;
