-- A partner is filed once per identity, and the identity a filing carries is the
-- value of the field the release names -- the code the onboarding minted by
-- default, the name the partner trades as for a release that reconciles its
-- master data by trading name. The claim is written in the transaction that
-- writes the partner, so the identity one partner holds cannot be taken by a
-- second filing and a refused filing leaves no claim behind.
CREATE TABLE IF NOT EXISTS partner_identity_claims (
  identity    text PRIMARY KEY,
  supplier_id text NOT NULL,
  claimed_at  timestamptz NOT NULL DEFAULT now()
);

-- The table is read by identity on every registration and swept with the other
-- registries by the outbox relay's retention pass.
CREATE INDEX IF NOT EXISTS idx_partner_identity_claims_claimed_at ON partner_identity_claims (claimed_at);
