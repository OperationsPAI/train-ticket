-- Daily statements are based on the immutable terminal occurrence date, not on
-- updatedAt. A later status query may update a snapshot without moving the
-- original payment or refund into a different channel statement.
CREATE INDEX IF NOT EXISTS idx_channel_order_statement_facts
    ON channel_order_snapshots (
        (data->>'channel'),
        (data->'amount'->>'currency'),
        (left(data->>'completedAt', 10)),
        id
    )
    WHERE data->>'status' = 'SUCCEEDED';

CREATE INDEX IF NOT EXISTS idx_channel_refund_statement_facts
    ON channel_refund_snapshots (
        (data->>'channel'),
        (data->'amount'->>'currency'),
        (left(data->>'completedAt', 10)),
        id
    )
    WHERE data->>'status' = 'SUCCEEDED';
