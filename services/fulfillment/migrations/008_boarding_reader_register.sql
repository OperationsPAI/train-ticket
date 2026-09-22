-- The readers this estate admits a boarding fact from. The register is the
-- estate's own data: the deployment that runs the estate stages a reader as a
-- row here, and the service reads the table once at startup instead of carrying
-- the list beside the check that measures a boarding fact against it.
CREATE TABLE IF NOT EXISTS boarding_reader_register (
  reader    text PRIMARY KEY,
  staged_at timestamptz NOT NULL DEFAULT now()
);
