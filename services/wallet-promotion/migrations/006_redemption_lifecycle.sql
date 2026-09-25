CREATE TABLE IF NOT EXISTS benefit_reservations (
    benefit_id text NOT NULL,
    reservation_ref text NOT NULL,
    status text NOT NULL,
    expires_at timestamptz NOT NULL,
    data jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (benefit_id, reservation_ref)
);

CREATE INDEX IF NOT EXISTS idx_benefit_reservations_expiry
    ON benefit_reservations (expires_at)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS reservation_releases (
    release_id text PRIMARY KEY,
    benefit_id text NOT NULL,
    reservation_ref text NOT NULL,
    release_ref text NOT NULL,
    data jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (benefit_id, release_ref)
);

ALTER TABLE redemption_reversals ADD COLUMN IF NOT EXISTS reason_key text;
ALTER TABLE redemption_reversals ADD COLUMN IF NOT EXISTS reversal_ref text;
UPDATE redemption_reversals
   SET reason_key = concat_ws('|',
       data->'businessReason'->>'reasonType',
       data->'businessReason'->>'reasonCode',
       coalesce(data->'businessReason'->>'referenceType',''),
       coalesce(data->'businessReason'->>'referenceId',''))
 WHERE reason_key IS NULL;
ALTER TABLE redemption_reversals ALTER COLUMN reason_key SET NOT NULL;
UPDATE redemption_reversals SET reversal_ref=reason_key WHERE reversal_ref IS NULL;
ALTER TABLE redemption_reversals ALTER COLUMN reversal_ref SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_redemption_reversal_reference
    ON redemption_reversals (redemption_id, reversal_ref);

ALTER TABLE benefit_redemptions ADD COLUMN IF NOT EXISTS redemption_ref text;
UPDATE benefit_redemptions SET redemption_ref=data->>'redemptionRef' WHERE redemption_ref IS NULL;
ALTER TABLE benefit_redemptions ALTER COLUMN redemption_ref SET NOT NULL;
ALTER TABLE benefit_redemptions DROP CONSTRAINT IF EXISTS benefit_redemptions_benefit_id_reason_key_key;
CREATE UNIQUE INDEX IF NOT EXISTS idx_benefit_redemption_reference
    ON benefit_redemptions (benefit_id, redemption_ref);

DROP INDEX IF EXISTS idx_promotion_expiry;
CREATE INDEX idx_promotion_expiry
    ON promotion_instrument_snapshots ((data->>'validUntil'))
    WHERE data->>'status' IN ('ISSUED','RESERVED','REDEEMED','RELEASED','REVERSED');
