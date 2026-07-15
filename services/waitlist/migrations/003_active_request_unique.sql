CREATE UNIQUE INDEX IF NOT EXISTS waitlist_active_request_unique_idx
    ON waitlist_entries ((traveler_refs->>0), (data->>'intentFingerprint'))
    WHERE status IN ('DRAFT', 'QUEUED', 'MATCHING', 'SUSPENDED');
