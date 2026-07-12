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

seed_corridor() { # -> sets WL_P_A/WL_P_B/WL_N_A/WL_N_B (fresh per run)
  local tag=$(python3 -c "import uuid; print(uuid.uuid4().hex[:6].upper())")
  req POST place-network /api/v1/places "{\"canonicalName\":\"WaitlistA $tag\",\"placeType\":\"CITY\",\"code\":\"W${tag:0:2}A\",\"timezone\":\"Asia/Shanghai\"}"
  check_code 201 "create waitlist place A"
  WL_P_A=$(jget "['placeId']")
  req POST place-network /api/v1/places "{\"canonicalName\":\"WaitlistB $tag\",\"placeType\":\"CITY\",\"code\":\"W${tag:0:2}B\",\"timezone\":\"Asia/Shanghai\"}"
  check_code 201 "create waitlist place B"
  WL_P_B=$(jget "['placeId']")
  req POST place-network /api/v1/transport-nodes "{\"placeId\":\"$WL_P_A\",\"displayName\":\"Waitlist A $tag\",\"servingModes\":[\"RAIL\"]}"
  check_code 201 "create waitlist node A"
  WL_N_A=$(jget "['nodeId']")
  req POST place-network /api/v1/transport-nodes "{\"placeId\":\"$WL_P_B\",\"displayName\":\"Waitlist B $tag\",\"servingModes\":[\"RAIL\"]}"
  check_code 201 "create waitlist node B"
  WL_N_B=$(jget "['nodeId']")
  sleep 3
}

create_traveler() {
  local acct=$1 suffix=$2
  req POST traveler-profile /api/v1/travelers "{\"accountId\":\"$acct\",\"travelerType\":\"ADULT\",\"givenName\":\"Wait\",\"familyName\":\"List$suffix\"}"
  check_code 201 "create traveler $suffix"
  CREATED_TRAVELER=$(jget "['travelerId']")
  sleep 3
}

seed_segment() { # service-number -> sets SEEDED_SS/SEEDED_SEG
  local svc=$1 ss seg
  req POST service-plan /api/v1/scheduled-services "{\"carrierId\":\"car-$(uuid7)\",\"serviceNumber\":\"$svc\",\"departureTime\":\"2026-08-02T09:00:00Z\",\"arrivalTime\":\"2026-08-02T14:30:00Z\",\"originNodeId\":\"$WL_N_A\",\"destinationNodeId\":\"$WL_N_B\"}"
  check_code 201 "create waitlist scheduled service $svc"
  ss=$(jget "['scheduledServiceRef']")
  req POST service-plan /api/v1/service-segments "{\"scheduledServiceRef\":\"$ss\",\"originStopRef\":\"$WL_N_A\",\"destinationStopRef\":\"$WL_N_B\",\"departureTime\":\"2026-08-02T09:00:00Z\",\"arrivalTime\":\"2026-08-02T14:30:00Z\"}"
  check_code 201 "create waitlist segment $svc"
  seg=$(jget "['segmentRef']")
  sleep 3
  SEEDED_SS=$ss
  SEEDED_SEG=$seg
}

find_itinerary_for_segment() { # traveler segment -> sets FOUND_ITIN
  local tvl=$1 seg=$2 itin=""
  for attempt in $(seq 1 20); do
    req POST trip-planning /api/v1/itineraries/search "{\"originRef\":\"$WL_P_A\",\"destinationRef\":\"$WL_P_B\",\"departureDate\":\"2026-08-02\",\"travelerRefs\":[\"$tvl\"],\"channel\":\"WEB\"}"
    if [ "$LAST_CODE" = 200 ]; then
      itin=$(printf '%s' "$RESP" | SEG_REF="$seg" python3 -c '
import os, sys, json
d=json.load(sys.stdin)
for item in d.get("itineraries", []):
    legs=item.get("legs") or []
    if legs and legs[0].get("serviceSegmentRef") == os.environ["SEG_REF"]:
        print(item.get("itineraryRef", "")); break
')
      [ -n "$itin" ] && break
    fi
    sleep 3
  done
  FOUND_ITIN=$itin
  [ -n "$FOUND_ITIN" ] && ok "trip-planning exposes segment $seg" || bad "trip-planning did not expose $seg"
}

make_offer() { # traveler -> sets OFFER_ID/OFFER_VER (real quote->offer chain)
  local acct=$1 tvl=$2 seg=$3
  find_itinerary_for_segment "$tvl" "$seg"
  req POST fare-pricing /api/v1/fare-quotes "{\"travelerRefs\":[\"$tvl\"],\"channel\":\"WEB\",\"segmentRefs\":[\"$seg\"]}"
  check_code 201 "identity real quote"
  sleep 3
  req POST offer-management /api/v1/offers "{\"accountId\":\"$acct\",\"channelId\":\"WEB\",\"itineraryRef\":\"$FOUND_ITIN\",\"travelerRefs\":[\"$tvl\"]}"
  check_code 201 "identity real offer"
  OFFER_ID=$(jget "['offerId']"); OFFER_VER=$(jget "['offerVersion']")
}

RUN=$(uuid7 | tail -c 13)

register_credential() {
  local traveler=$1 tail=$2 idem_doc="1101011990${RUN:0:4}0$2"
  req POST identity-verification /api/v1/identity-verification/credentials "{\"travelerId\":\"$traveler\",\"profileSnapshotVersion\":\"snap-1\",\"documentType\":\"ID_CARD\",\"maskedDocumentNo\":\"110***********$tail\",\"documentHash\":\"$(hash_doc "$idem_doc")\",\"canonicalNameHash\":\"name-$traveler\",\"validUntil\":\"2027-01-01T00:00:00Z\"}"
  check_code 201 "credential registered $traveler"
  CRED_ID=$(jget "['credentialRecordId']")
}

# Pass chain via document hash tail 5.
register_credential tvl-e2e-pass-$RUN 5
PASS_CRED=$CRED_ID
# compute fingerprint as service does
PASS_FP=$(python3 - "$RUN" <<'PY'
import hashlib, sys
run = sys.argv[1]
doc = '1101011990' + run[:4] + '05'
parts = ['name-tvl-e2e-pass-' + run, 'ID_CARD', hashlib.sha256(doc.encode()).hexdigest() + '5', '', '2027-01-01T00:00:00Z', '', 'snap-1']
print(hashlib.sha256('|'.join(parts).encode()).hexdigest())
PY
)
req POST identity-verification /api/v1/identity-verification/verification-cases "{\"travelerId\":\"tvl-e2e-pass-$RUN\",\"credentialRecordId\":\"$PASS_CRED\",\"purpose\":\"ORDER_CREATION\",\"materialFingerprint\":\"$PASS_FP\",\"simPolicyVersion\":\"sim-tail-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
check_code 201 "verification pass case"
[ "$(jget "['status']")" = PASSED ] && ok "tail pass" || bad "tail pass"

register_credential tvl-e2e-reject-$RUN 7
REJECT_CRED=$CRED_ID
REJECT_FP=$(python3 - "$RUN" <<'PY'
import hashlib, sys
run = sys.argv[1]
doc = '1101011990' + run[:4] + '07'
parts = ['name-tvl-e2e-reject-' + run, 'ID_CARD', hashlib.sha256(doc.encode()).hexdigest() + '7', '', '2027-01-01T00:00:00Z', '', 'snap-1']
print(hashlib.sha256('|'.join(parts).encode()).hexdigest())
PY
)
req POST identity-verification /api/v1/identity-verification/verification-cases "{\"travelerId\":\"tvl-e2e-reject-$RUN\",\"credentialRecordId\":\"$REJECT_CRED\",\"purpose\":\"ORDER_CREATION\",\"materialFingerprint\":\"$REJECT_FP\",\"simPolicyVersion\":\"sim-tail-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
check_code 201 "verification reject case"
[ "$(jget "['status']")" = FAILED ] && ok "tail reject" || bad "tail reject"

register_credential tvl-e2e-manual-$RUN 9
MANUAL_CRED=$CRED_ID
MANUAL_FP=$(python3 - "$RUN" <<'PY'
import hashlib, sys
run = sys.argv[1]
doc = '1101011990' + run[:4] + '09'
parts = ['name-tvl-e2e-manual-' + run, 'ID_CARD', hashlib.sha256(doc.encode()).hexdigest() + '9', '', '2027-01-01T00:00:00Z', '', 'snap-1']
print(hashlib.sha256('|'.join(parts).encode()).hexdigest())
PY
)
req POST identity-verification /api/v1/identity-verification/verification-cases "{\"travelerId\":\"tvl-e2e-manual-$RUN\",\"credentialRecordId\":\"$MANUAL_CRED\",\"purpose\":\"ORDER_CREATION\",\"materialFingerprint\":\"$MANUAL_FP\",\"simPolicyVersion\":\"sim-tail-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
MANUAL_CASE=$(jget "['verificationCaseId']")
[ "$(jget "['status']")" = MANUAL_REVIEW_REQUIRED ] && ok "tail manual" || bad "tail manual"
req POST identity-verification "/api/v1/identity-verification/verification-cases/$MANUAL_CASE/manual-override" '{}'
check_code 200 "manual override"

req POST identity-verification /api/v1/identity-verification/eligibility-certificates "{\"travelerId\":\"tvl-e2e-pass-$RUN\",\"credentialRecordId\":\"$PASS_CRED\",\"eligibilityType\":\"STUDENT\",\"validFrom\":\"2026-01-01T00:00:00Z\",\"validUntil\":\"2026-12-31T00:00:00Z\",\"policyYear\":\"2026\",\"policyVersion\":\"student-v1\",\"annualUsageLimit\":4,\"applicableProductCodes\":[\"TRAIN\",\"identity-rail\"],\"certificateHash\":\"cert-e2e\",\"evidenceHash\":\"evidence-e2e\"}"
check_code 201 "certificate registered"
req GET identity-verification "/api/v1/identity-verification/eligibility-certificates?travelerId=tvl-e2e-pass-$RUN&eligibilityType=STUDENT&journeyDate=2026-02-01&productCode=TRAIN" ''
check_code 200 "certificate query"
[ "$(jget "['total']")" = 1 ] && ok "certificate visible to fare-pricing" || bad "certificate visible"

req POST identity-verification /api/v1/identity-verification/pre-order-checks "{\"orderIntentId\":\"oint-e2e-$RUN-missing\",\"accountId\":\"acct-e2e-$RUN\",\"offerId\":\"off-e2e\",\"offerVersion\":1,\"travelerRefs\":[\"tvl-e2e-missing-$RUN\"],\"segmentRefs\":[\"seg-e2e\"],\"journeyDate\":\"2026-02-01\",\"productCode\":\"TRAIN\",\"limitPolicyVersion\":\"limit-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
[ "$(jget "['result']")" = REJECT ] && ok "unverified rejected" || bad "unverified rejected"
req POST identity-verification /api/v1/identity-verification/pre-order-checks "{\"orderIntentId\":\"oint-e2e-$RUN-pass\",\"accountId\":\"acct-e2e-$RUN\",\"offerId\":\"off-e2e\",\"offerVersion\":1,\"travelerRefs\":[\"tvl-e2e-pass-$RUN\"],\"segmentRefs\":[\"seg-e2e\"],\"journeyDate\":\"2026-02-01\",\"productCode\":\"TRAIN\",\"limitPolicyVersion\":\"limit-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
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
req POST fare-pricing /api/v1/fare-quotes "{\"travelerRefs\":[\"tvl-e2e-pass-$RUN\"],\"channel\":\"WEB\",\"segmentRefs\":[\"seg-e2e-fare\"],\"productCode\":\"identity-rail\"}"
check_code 201 "fare-pricing identity quote"
[ "$(jget "['breakdown']['total']['minorUnits']")" = 8000 ] && ok "fare-pricing applied certificate discount" || bad "fare-pricing certificate discount"


# Journey-order hook in default enabled mode: reject unverified, accept verified.
# Real-chain journey-order integration: corridor + travelers + real offer.
seed_corridor
ACCT_ID="acc-$(uuid7)"
create_traveler "$ACCT_ID" IDU; UNVERIFIED_TVL=$CREATED_TRAVELER
create_traveler "$ACCT_ID" IDV; VERIFIED_TVL=$CREATED_TRAVELER
verify_traveler "$VERIFIED_TVL"
seed_segment "GID${RANDOM}"; ID_SEG=$SEEDED_SEG

make_offer "$ACCT_ID" "$UNVERIFIED_TVL" "$ID_SEG"
req POST journey-order /api/v1/journey-orders "{\"accountId\":\"$ACCT_ID\",\"offerId\":\"$OFFER_ID\",\"offerVersion\":${OFFER_VER:-1},\"travelerRefs\":[\"$UNVERIFIED_TVL\"],\"segmentRefs\":[\"$ID_SEG\"]}"
{ [ "$LAST_CODE" = 422 ] || [ "$LAST_CODE" = 412 ]; } && ok "journey-order unverified rejection" || bad "journey-order unverified rejection got $LAST_CODE"

make_offer "$ACCT_ID" "$VERIFIED_TVL" "$ID_SEG"
req POST journey-order /api/v1/journey-orders "{\"accountId\":\"$ACCT_ID\",\"offerId\":\"$OFFER_ID\",\"offerVersion\":${OFFER_VER:-1},\"travelerRefs\":[\"$VERIFIED_TVL\"],\"segmentRefs\":[\"$ID_SEG\"]}"
check_code 201 "journey-order accepts verified traveler"
VERIFIED_ORDER=$(jget "['orderId']")

# Hook-disable regression lives in journey-order unit tests: in-cluster env
# flips mid-script race rollouts across runs (orchestrator ruling).

# Reservation confirmation/release and purchase-limit event class assertions.
req POST identity-verification /api/v1/identity-verification/pre-order-checks "{\"orderIntentId\":\"oint-e2e-$RUN-cert-confirm\",\"accountId\":\"acct-e2e-$RUN\",\"offerId\":\"off-e2e\",\"offerVersion\":1,\"travelerRefs\":[\"tvl-e2e-pass-$RUN\"],\"segmentRefs\":[\"seg-e2e\"],\"journeyDate\":\"2026-02-01\",\"productCode\":\"TRAIN\",\"requestedEligibilityTypes\":[\"STUDENT\"],\"limitPolicyVersion\":\"limit-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
check_code 201 "cert-confirm check created"
CONFIRM_CHECK=$(jget "['preOrderCheckId']")
CONFIRM_FACT=$(jget "['purchaseLimitFacts'][0]['purchaseLimitFactId']")
req POST identity-verification "/api/v1/identity-verification/pre-order-checks/$CONFIRM_CHECK/confirm" '{"journeyOrderId":"ord-e2e-identity"}'
check_code 200 "confirm identity pre-order"
req POST identity-verification /api/v1/identity-verification/pre-order-checks "{\"orderIntentId\":\"oint-e2e-$RUN-cert-release\",\"accountId\":\"acct-e2e-$RUN\",\"offerId\":\"off-e2e\",\"offerVersion\":1,\"travelerRefs\":[\"tvl-e2e-pass-$RUN\"],\"segmentRefs\":[\"seg-e2e\"],\"journeyDate\":\"2026-02-01\",\"productCode\":\"TRAIN\",\"requestedEligibilityTypes\":[\"STUDENT\"],\"limitPolicyVersion\":\"limit-v1\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}"
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
