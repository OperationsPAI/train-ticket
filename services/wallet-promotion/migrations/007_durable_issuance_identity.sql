ALTER TABLE compensation_issuances RENAME TO benefit_issuances;
ALTER TABLE benefit_issuances RENAME COLUMN case_id TO issuance_key;

UPDATE benefit_issuances
   SET issuance_key = 'POST_SALES_COMP|' || issuance_key
 WHERE issuance_key NOT LIKE 'POST_SALES_COMP|%';

INSERT INTO benefit_issuances(issuance_key, benefit_id, request, created_at)
SELECT CASE
           WHEN data->>'issuanceSource' = 'MANUAL_OPS' THEN concat_ws('|',
               data->>'issuanceSource',
               data->>'accountId',
               data->'businessReason'->>'reasonType',
               data->'businessReason'->>'reasonCode',
               coalesce(data->'businessReason'->>'referenceType',''),
               coalesce(data->'businessReason'->>'referenceId',''))
           ELSE concat_ws('|',
               data->>'issuanceSource',
               coalesce(data->>'caseId', data->'businessReason'->>'referenceId'))
       END,
       id,
       jsonb_build_object(
           'accountId', data->'accountId',
           'benefitType', data->'benefitType',
           'balanceType', data->'balanceType',
           'amount', data->'issuedAmount',
           'issuanceSource', data->'issuanceSource',
           'caseId', CASE
               WHEN data->>'issuanceSource' = 'MANUAL_OPS' THEN 'null'::jsonb
               ELSE to_jsonb(coalesce(data->>'caseId', data->'businessReason'->>'referenceId'))
           END,
           'applicableScope', data->'applicableScope',
           'redemptionRule', data->'redemptionRule',
           'revocationRule', data->'revocationRule',
           'validFrom', data->'validFrom',
           'validUntil', data->'validUntil',
           'businessReason', data->'businessReason'
       ),
       coalesce((data->>'createdAt')::timestamptz, updated_at)
  FROM promotion_instrument_snapshots
 WHERE data->>'issuanceSource' IS NOT NULL
ON CONFLICT DO NOTHING;
