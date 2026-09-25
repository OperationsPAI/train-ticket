-- Releases before real payout execution treated a local advice as delivered
-- money. Re-open those claims once, preserving the decision and assigning a
-- deterministic UUID-v7-shaped key so the owning Wallet API can safely resume.
WITH legacy AS (
  SELECT
    advice.id,
    policy.data->>'accountId' AS account_id,
    md5(concat_ws('|', advice.data->>'claimId', advice.data->>'policyId',
      advice.data->'amount'->>'currency', advice.data->'amount'->>'minorUnits',
      'WALLET_BENEFIT', advice.data->>'reasonCode', 'v1')) AS digest
  FROM payout_advice_snapshots advice
  JOIN policy_snapshots policy ON policy.id = advice.data->>'policyId'
  WHERE advice.data->>'status' = 'COMPLETED'
    AND COALESCE(advice.data->>'executionRef', '') = ''
)
UPDATE payout_advice_snapshots advice
SET data = jsonb_set(
      jsonb_set(
        jsonb_set(
          jsonb_set(
            jsonb_set(advice.data - 'executionRef' - 'completedAt', '{accountId}', to_jsonb(legacy.account_id), true),
            '{payoutTarget}', '"WALLET_BENEFIT"'::jsonb, true),
          '{status}', '"REQUESTED"'::jsonb, true),
        '{requestedAt}', to_jsonb(now()), true),
      '{idempotencyKey}',
      to_jsonb(substr(legacy.digest,1,8)||'-'||substr(legacy.digest,9,4)||'-7'||substr(legacy.digest,14,3)||'-8'||substr(legacy.digest,18,3)||'-'||substr(legacy.digest,21,12)), true)
FROM legacy
WHERE advice.id = legacy.id;

UPDATE claim_snapshots claim
SET data = (claim.data - 'payoutAdviceId') || jsonb_build_object(
      'status', 'APPROVED',
      'aggregateVersion', COALESCE((claim.data->>'aggregateVersion')::bigint, 0) + 1)
WHERE claim.data->>'status' = 'PAID_OUT'
  AND EXISTS (
    SELECT 1 FROM payout_advice_snapshots advice
    WHERE advice.data->>'claimId' = claim.id
      AND advice.data->>'status' = 'REQUESTED'
      AND COALESCE(advice.data->>'executionRef', '') = ''
  );

UPDATE policy_snapshots policy
SET data = policy.data || jsonb_build_object(
      'status', 'CLAIMED',
      'aggregateVersion', COALESCE((policy.data->>'aggregateVersion')::bigint, 0) + 1)
WHERE policy.data->>'status' = 'PAID_OUT'
  AND EXISTS (
    SELECT 1
    FROM claim_snapshots claim
    JOIN payout_advice_snapshots advice ON advice.data->>'claimId' = claim.id
    WHERE claim.data->>'policyId' = policy.id
      AND claim.data->>'status' = 'APPROVED'
      AND advice.data->>'status' = 'REQUESTED'
      AND COALESCE(advice.data->>'executionRef', '') = ''
  );
