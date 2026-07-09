#!/usr/bin/env bash
set -uo pipefail
DIR=$(cd "$(dirname "$0")" && pwd)
. "$DIR/lib.sh"
ensure_curl_pod

hash_doc() { python3 - "$1" <<'PY'
import hashlib,sys
print(hashlib.sha256(sys.argv[1].encode()).hexdigest()+sys.argv[1][-1])
PY
}

register_credential() {
  local traveler=$1 tail=$2 idem_doc="11010119900101000$2"
  req POST identity-verification /api/v1/identity-verification/credentials "{\"travelerId\":\"$traveler\",\"profileSnapshotVersion\":\"snap-1\",\"documentType\":\"ID_CARD\",\"maskedDocumentNo\":\"110***********$tail\",\"documentHash\":\"$(hash_doc "$idem_doc")\",\"canonicalNameHash\":\"name-$traveler\",\"validUntil\":\"2027-01-01T00:00:00Z\"}"
  check_code 201 "credential registered $traveler"
  CRED_ID=$(jget "['credentialRecordId']")
}

# Pass chain via document hash tail 5.
register_credential tvl-e2e-pass 5
PASS_CRED=$CRED_ID
# compute fingerprint as service does
PASS_FP=$(python3 - <<'PY'
import hashlib
parts=['name-tvl-e2e-pass','ID_CARD',hashlib.sha256('110101199001010005'.encode()).hexdigest()+'5','','2027-01-01T00:00:00Z','','snap-1']
print(hashlib.sha256('|'.join(parts).encode()).hexdigest())
PY
)
req POST identity-verification /api/v1/identity-verification/verification-cases "{\"travelerId\":\"tvl-e2e-pass\",\"credentialRecordId\":\"$PASS_CRED\",\"purpose\":\"ORDER_CREATION\",\"materialFingerprint\":\"$PASS_FP\",\"simPolicyVersion\":\"sim-tail-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
check_code 201 "verification pass case"
[ "$(jget "['status']")" = PASSED ] && ok "tail pass" || bad "tail pass"

register_credential tvl-e2e-reject 7
REJECT_CRED=$CRED_ID
REJECT_FP=$(python3 - <<'PY'
import hashlib
parts=['name-tvl-e2e-reject','ID_CARD',hashlib.sha256('110101199001010007'.encode()).hexdigest()+'7','','2027-01-01T00:00:00Z','','snap-1']
print(hashlib.sha256('|'.join(parts).encode()).hexdigest())
PY
)
req POST identity-verification /api/v1/identity-verification/verification-cases "{\"travelerId\":\"tvl-e2e-reject\",\"credentialRecordId\":\"$REJECT_CRED\",\"purpose\":\"ORDER_CREATION\",\"materialFingerprint\":\"$REJECT_FP\",\"simPolicyVersion\":\"sim-tail-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
check_code 201 "verification reject case"
[ "$(jget "['status']")" = FAILED ] && ok "tail reject" || bad "tail reject"

register_credential tvl-e2e-manual 9
MANUAL_CRED=$CRED_ID
MANUAL_FP=$(python3 - <<'PY'
import hashlib
parts=['name-tvl-e2e-manual','ID_CARD',hashlib.sha256('110101199001010009'.encode()).hexdigest()+'9','','2027-01-01T00:00:00Z','','snap-1']
print(hashlib.sha256('|'.join(parts).encode()).hexdigest())
PY
)
req POST identity-verification /api/v1/identity-verification/verification-cases "{\"travelerId\":\"tvl-e2e-manual\",\"credentialRecordId\":\"$MANUAL_CRED\",\"purpose\":\"ORDER_CREATION\",\"materialFingerprint\":\"$MANUAL_FP\",\"simPolicyVersion\":\"sim-tail-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
MANUAL_CASE=$(jget "['verificationCaseId']")
[ "$(jget "['status']")" = MANUAL_REVIEW_REQUIRED ] && ok "tail manual" || bad "tail manual"
req POST identity-verification "/api/v1/identity-verification/verification-cases/$MANUAL_CASE/manual-override" '{}'
check_code 200 "manual override"

req POST identity-verification /api/v1/identity-verification/eligibility-certificates "{\"travelerId\":\"tvl-e2e-pass\",\"credentialRecordId\":\"$PASS_CRED\",\"eligibilityType\":\"STUDENT\",\"validFrom\":\"2026-01-01T00:00:00Z\",\"validUntil\":\"2026-12-31T00:00:00Z\",\"policyYear\":\"2026\",\"policyVersion\":\"student-v1\",\"annualUsageLimit\":4,\"applicableProductCodes\":[\"TRAIN\",\"identity-rail\"],\"certificateHash\":\"cert-e2e\",\"evidenceHash\":\"evidence-e2e\"}"
check_code 201 "certificate registered"
req GET identity-verification '/api/v1/identity-verification/eligibility-certificates?travelerId=tvl-e2e-pass&eligibilityType=STUDENT&journeyDate=2026-02-01&productCode=TRAIN' ''
check_code 200 "certificate query"
[ "$(jget "['total']")" = 1 ] && ok "certificate visible to fare-pricing" || bad "certificate visible"

req POST identity-verification /api/v1/identity-verification/pre-order-checks "{\"orderIntentId\":\"oint-e2e-missing\",\"accountId\":\"acct-e2e\",\"offerId\":\"off-e2e\",\"offerVersion\":1,\"travelerRefs\":[\"tvl-e2e-missing\"],\"segmentRefs\":[\"seg-e2e\"],\"journeyDate\":\"2026-02-01\",\"productCode\":\"TRAIN\",\"limitPolicyVersion\":\"limit-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
[ "$(jget "['result']")" = REJECT ] && ok "unverified rejected" || bad "unverified rejected"
req POST identity-verification /api/v1/identity-verification/pre-order-checks "{\"orderIntentId\":\"oint-e2e-pass\",\"accountId\":\"acct-e2e\",\"offerId\":\"off-e2e\",\"offerVersion\":1,\"travelerRefs\":[\"tvl-e2e-pass\"],\"segmentRefs\":[\"seg-e2e\"],\"journeyDate\":\"2026-02-01\",\"productCode\":\"TRAIN\",\"limitPolicyVersion\":\"limit-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
[ "$(jget "['result']")" = PASS ] && ok "verified pre-order pass" || bad "verified pre-order pass"
[ "$(xlen events:identity-verification)" -gt 0 ] && ok "identity events emitted" || bad "identity events emitted"

# Fare-pricing certificate lookup assertion through the existing quote API.
IDENTITY_RULE_VERSION="identity-e2e-$(date -u +%Y%m%d%H%M%S)"
IDENTITY_RULE_BODY=$(cat <<JSON
{"supplierId":"supplier-identity-e2e","contractId":"contract-identity-e2e","productCode":"identity-rail","mode":"rail","channel":"WEB","version":"$IDENTITY_RULE_VERSION","effectiveWindow":{"startsAt":"2026-01-01T00:00:00Z","endsAt":"2026-12-31T00:00:00Z"},"rules":[{"ruleId":"base-identity-e2e","kind":"base_fare","amount":{"currency":"CNY","minorUnits":10000},"explanation":{"code":"fare.base.identity","parameters":{"source":"21-identity"}},"refundable":true},{"ruleId":"student-identity-e2e","kind":"discount","amount":{"currency":"CNY","minorUnits":2000},"explanation":{"code":"fare.discount.student","parameters":{"eligibilityType":"STUDENT"}},"refundable":true}]}
JSON
)
req POST fare-pricing /api/v1/fare-rule-sets "$IDENTITY_RULE_BODY"
check_code 201 "create identity fare rule set"
IDENTITY_RULE_SET=$(jget "['ruleSetId']")
req POST fare-pricing "/api/v1/fare-rule-sets/$IDENTITY_RULE_SET/publish" '{}'
check_code 200 "publish identity fare rule set"
req POST fare-pricing /api/v1/fare-quotes "{\"travelerRefs\":[\"tvl-e2e-pass\"],\"channel\":\"WEB\",\"segmentRefs\":[\"seg-e2e-fare\"],\"productCode\":\"identity-rail\"}"
check_code 201 "fare-pricing identity quote"
[ "$(jget "['breakdown']['total']['minorUnits']")" = 8000 ] && ok "fare-pricing applied certificate discount" || bad "fare-pricing certificate discount"


# Journey-order hook in default enabled mode: reject unverified, accept verified.
req POST journey-order /api/v1/journey-orders '{"accountId":"acct-e2e-identity-enabled","offerId":"off-e2e-identity-enabled-missing","offerVersion":1,"travelerRefs":["tvl-e2e-missing"],"segmentRefs":["seg-e2e"],"journeyDate":"2026-02-01","productCode":"TRAIN"}'
[ "$LAST_CODE" = 412 ] && ok "journey-order rejects unverified traveler" || bad "journey-order unverified rejection got $LAST_CODE"
req POST journey-order /api/v1/journey-orders '{"accountId":"acct-e2e-identity-enabled","offerId":"off-e2e-identity-enabled-pass","offerVersion":1,"travelerRefs":["tvl-e2e-pass"],"segmentRefs":["seg-e2e"],"journeyDate":"2026-02-01","productCode":"TRAIN"}'
check_code 201 "journey-order accepts verified traveler"

# Journey-order hook disabled-state regression: temporarily disable the adapter,
# prove an unverified order is creatable, then restore the default enabled state.
k set env deploy/journey-order IDENTITY_VERIFICATION_PREORDER_ENABLED=false >/dev/null
if k rollout status deploy/journey-order --timeout=180s >/dev/null 2>&1; then
  req POST journey-order /api/v1/journey-orders '{"accountId":"acct-e2e-identity","offerId":"off-e2e-identity","offerVersion":1,"travelerRefs":["tvl-e2e-missing"],"segmentRefs":["seg-e2e"],"journeyDate":"2026-02-01","productCode":"TRAIN"}'
  [ "$LAST_CODE" = 201 ] && ok "journey-order identity switch disabled regression" || bad "journey-order disabled regression got $LAST_CODE"
  DISABLED_ORDER=$(jget "['orderId']")
  req GET journey-order "/api/v1/journey-orders/$DISABLED_ORDER"
  check_code 200 "disabled-hook order remains readable"
else
  bad "journey-order rollout after disabling identity hook"
fi
k set env deploy/journey-order IDENTITY_VERIFICATION_PREORDER_ENABLED=true >/dev/null
k rollout status deploy/journey-order --timeout=180s >/dev/null 2>&1 || bad "journey-order rollout after restoring identity hook"

# Reservation confirmation/release and purchase-limit event class assertions.
req POST identity-verification /api/v1/identity-verification/pre-order-checks "{\"orderIntentId\":\"oint-e2e-cert-confirm\",\"accountId\":\"acct-e2e\",\"offerId\":\"off-e2e\",\"offerVersion\":1,\"travelerRefs\":[\"tvl-e2e-pass\"],\"segmentRefs\":[\"seg-e2e\"],\"journeyDate\":\"2026-02-01\",\"productCode\":\"TRAIN\",\"requestedEligibilityTypes\":[\"STUDENT\"],\"limitPolicyVersion\":\"limit-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
CONFIRM_CHECK=$(jget "['preOrderCheckId']")
CONFIRM_FACT=$(jget "['purchaseLimitFacts'][0]['purchaseLimitFactId']")
req POST identity-verification "/api/v1/identity-verification/pre-order-checks/$CONFIRM_CHECK/confirm" '{"journeyOrderId":"ord-e2e-identity"}'
check_code 200 "confirm identity pre-order"
req POST identity-verification /api/v1/identity-verification/pre-order-checks "{\"orderIntentId\":\"oint-e2e-cert-release\",\"accountId\":\"acct-e2e\",\"offerId\":\"off-e2e\",\"offerVersion\":1,\"travelerRefs\":[\"tvl-e2e-pass\"],\"segmentRefs\":[\"seg-e2e\"],\"journeyDate\":\"2026-02-01\",\"productCode\":\"TRAIN\",\"requestedEligibilityTypes\":[\"STUDENT\"],\"limitPolicyVersion\":\"limit-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
RELEASE_CHECK=$(jget "['preOrderCheckId']")
req POST identity-verification "/api/v1/identity-verification/pre-order-checks/$RELEASE_CHECK/release" '{"releaseReason":"E2E_RELEASE"}'
check_code 200 "release identity pre-order"

stream_has_identity_event() {
  local event_type=$1
  k exec "$(redis_pod)" -- redis-cli XREVRANGE events:identity-verification + - COUNT 120 > /tmp/identity-events.txt 2>/dev/null
  EVENT_TYPE="$event_type" python3 - <<'PY'
import json, os, re
raw = open('/tmp/identity-events.txt').read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e = json.loads(m.group(0).encode().decode('unicode_escape'))
    except Exception:
        continue
    if e.get('eventType') == os.environ['EVENT_TYPE']:
        print('yes')
        break
PY
}
for et in PurchaseLimitFactRecorded PurchaseLimitFactConfirmed PurchaseLimitFactReleased EligibilityUsageReserved EligibilityUsageConfirmed EligibilityUsageReleased; do
  [ "$(stream_has_identity_event "$et")" = yes ] && ok "$et observed" || bad "$et not observed"
done

summary
