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

req POST identity-verification /api/v1/identity-verification/eligibility-certificates "{\"travelerId\":\"tvl-e2e-pass\",\"credentialRecordId\":\"$PASS_CRED\",\"eligibilityType\":\"STUDENT\",\"validFrom\":\"2026-01-01T00:00:00Z\",\"validUntil\":\"2026-12-31T00:00:00Z\",\"policyYear\":\"2026\",\"policyVersion\":\"student-v1\",\"annualUsageLimit\":4,\"applicableProductCodes\":[\"TRAIN\"],\"certificateHash\":\"cert-e2e\",\"evidenceHash\":\"evidence-e2e\"}"
check_code 201 "certificate registered"
req GET identity-verification '/api/v1/identity-verification/eligibility-certificates?travelerId=tvl-e2e-pass&eligibilityType=STUDENT&journeyDate=2026-02-01&productCode=TRAIN' ''
check_code 200 "certificate query"
[ "$(jget "['total']")" = 1 ] && ok "certificate visible to fare-pricing" || bad "certificate visible"

req POST identity-verification /api/v1/identity-verification/pre-order-checks "{\"orderIntentId\":\"oint-e2e-missing\",\"accountId\":\"acct-e2e\",\"offerId\":\"off-e2e\",\"offerVersion\":1,\"travelerRefs\":[\"tvl-e2e-missing\"],\"segmentRefs\":[\"seg-e2e\"],\"journeyDate\":\"2026-02-01\",\"productCode\":\"TRAIN\",\"limitPolicyVersion\":\"limit-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
[ "$(jget "['result']")" = REJECT ] && ok "unverified rejected" || bad "unverified rejected"
req POST identity-verification /api/v1/identity-verification/pre-order-checks "{\"orderIntentId\":\"oint-e2e-pass\",\"accountId\":\"acct-e2e\",\"offerId\":\"off-e2e\",\"offerVersion\":1,\"travelerRefs\":[\"tvl-e2e-pass\"],\"segmentRefs\":[\"seg-e2e\"],\"journeyDate\":\"2026-02-01\",\"productCode\":\"TRAIN\",\"limitPolicyVersion\":\"limit-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
[ "$(jget "['result']")" = PASS ] && ok "verified pre-order pass" || bad "verified pre-order pass"
[ "$(xlen events:identity-verification)" -gt 0 ] && ok "identity events emitted" || bad "identity events emitted"

summary
