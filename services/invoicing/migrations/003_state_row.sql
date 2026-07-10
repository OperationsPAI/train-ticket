-- Single-row service state: every command/consume transaction locks this row
-- (SELECT ... FOR UPDATE), applies the domain change, writes the snapshot
-- back, and appends outbox entries atomically.
CREATE TABLE IF NOT EXISTS invoicing_state (id int PRIMARY KEY, data jsonb NOT NULL DEFAULT '{}'::jsonb);
