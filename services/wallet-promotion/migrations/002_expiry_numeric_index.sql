-- Internal snapshots store Instants as epoch decimals (repo-wide Java
-- convention, same as payment). The expiry index/query compare numerically;
-- text->numeric casts are IMMUTABLE (unlike timestamptz casts, REQ-081A).
DROP INDEX IF EXISTS idx_promotion_expiry;
CREATE INDEX IF NOT EXISTS idx_promotion_expiry ON promotion_instrument_snapshots (((data->>'validUntil')::numeric)) WHERE data->>'status' IN ('ISSUED','RESERVED','RELEASED');
