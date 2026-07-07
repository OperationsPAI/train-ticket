-- case_timelines is managed by ts-kit SnapshotRepository, which requires the
-- ruling's snapshot shape (id/version/data/updated_at); 001 shipped case_id.
ALTER TABLE case_timelines RENAME COLUMN case_id TO id;
