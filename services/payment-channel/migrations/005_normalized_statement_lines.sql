-- Statement metadata remains in channel_statement_snapshots. Immutable daily
-- line facts live separately so summary reads and freeze updates have bounded
-- row size as the number of payments and refunds grows.
CREATE TABLE IF NOT EXISTS channel_statement_lines (
    statement_id text NOT NULL,
    line_ordinal bigint NOT NULL CHECK (line_ordinal > 0),
    statement_line_id text NOT NULL,
    line_type text NOT NULL CHECK (line_type IN ('PAYMENT', 'REFUND', 'FEE', 'ADJUSTMENT')),
    source_id text NOT NULL,
    evidence_hash text NOT NULL,
    occurred_at timestamptz NOT NULL,
    data jsonb NOT NULL,
    PRIMARY KEY (statement_id, line_ordinal),
    UNIQUE (statement_id, statement_line_id),
    FOREIGN KEY (statement_id) REFERENCES channel_statement_snapshots(id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE IF NOT EXISTS channel_statement_identities (
    identity_key text PRIMARY KEY,
    statement_id text NOT NULL UNIQUE REFERENCES channel_statement_snapshots(id)
        DEFERRABLE INITIALLY DEFERRED,
    claimed_at timestamptz NOT NULL DEFAULT now()
);

-- Old code could create more than one statement for the same business key.
-- Preserve every statement and its reconciliation references, while choosing
-- the earliest record as the canonical target for all future generation replay.
INSERT INTO channel_statement_identities (identity_key, statement_id)
SELECT DISTINCT ON (identity_key) identity_key, id
FROM (
    SELECT concat_ws(chr(31), data->>'channel', data->>'statementDate', data->>'currency', data->>'seedVersion') AS identity_key,
           id,
           data->>'createdAt' AS created_at
    FROM channel_statement_snapshots
    WHERE data ? 'channel' AND data ? 'statementDate' AND data ? 'currency' AND data ? 'seedVersion'
) statements
ORDER BY identity_key, created_at, id
ON CONFLICT (identity_key) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_channel_statement_lines_lookup
    ON channel_statement_lines (statement_id, statement_line_id);

CREATE INDEX IF NOT EXISTS idx_channel_statement_lines_source
    ON channel_statement_lines (statement_id, line_type, source_id);

CREATE OR REPLACE FUNCTION protect_channel_statement_lines()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF EXISTS (
            SELECT 1 FROM channel_statement_snapshots
            WHERE id = NEW.statement_id
              AND data->>'lineStorage' = 'NORMALIZED_V1'
        ) THEN
            RAISE EXCEPTION 'normalized channel statement lines are immutable';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'channel statement lines are immutable';
END;
$$;

DROP TRIGGER IF EXISTS trg_protect_channel_statement_lines
    ON channel_statement_lines;
CREATE TRIGGER trg_protect_channel_statement_lines
    BEFORE INSERT OR UPDATE OR DELETE ON channel_statement_lines
    FOR EACH ROW EXECUTE FUNCTION protect_channel_statement_lines();

-- Existing JSON-backed statements remain readable through the compatibility
-- query until the explicit online backfill has copied and verified their lines.
