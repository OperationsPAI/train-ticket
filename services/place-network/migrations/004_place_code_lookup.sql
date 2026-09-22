CREATE INDEX IF NOT EXISTS idx_place_snapshots_code_status_created_id
  ON place_snapshots ((data->>'code'), (data->>'status'), (data->>'createdAt'), (data->>'id'));
