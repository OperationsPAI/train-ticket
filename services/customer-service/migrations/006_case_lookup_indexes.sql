CREATE INDEX IF NOT EXISTS support_case_reference_idx
    ON support_case_snapshots USING gin (data jsonb_path_ops);
CREATE INDEX IF NOT EXISTS support_case_evaluation_idx
    ON support_case_snapshots (updated_at, id)
    WHERE COALESCE(data->>'status', '') NOT IN ('Resolved', 'Closed');
