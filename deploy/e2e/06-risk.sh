#!/usr/bin/env bash
# Risk chain: JourneyOrderCreated -> RiskAssessmentResult/RiskBlockApplied; manual lift permits a fresh order.
cd "$(dirname "$0")" && . ./lib.sh
. ./.refs.env
ensure_curl_pod

ACCT="acc-risk-$(uuid7)"

find_risk_event() {
  local type=$1 subject=$2 count=${3:-50}
  k exec "$(redis_pod)" -- redis-cli XREVRANGE events:risk-compliance + - COUNT "$count" > /tmp/riskstream.txt
  TYPE="$type" SUBJECT="$subject" python3 - << 'PY'
import json, os, re
raw = open('/tmp/riskstream.txt').read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e = json.loads(m.group(0).encode().decode('unicode_escape'))
    except Exception:
        continue
    payload = e.get('payload', {})
    if e.get('eventType') == os.environ['TYPE'] and payload.get('subjectRef') == os.environ['SUBJECT']:
        print(json.dumps(e)); break
PY
}

wait_event() {
  local type=$1 subject=$2 found=""
  for attempt in 1 2 3 4 5 6; do
    found=$(find_risk_event "$type" "$subject")
    [ -n "$found" ] && break
    sleep 3
  done
  [ -n "$found" ]
}

create_order() {
  local label=$1
  req POST journey-order /api/v1/journey-orders "{\"accountId\":\"$ACCT\",\"offerId\":\"$OFFER\",\"offerVersion\":${OFFERV:-1},\"travelerRefs\":[\"$TVL\"],\"segmentRefs\":[\"$SEG\"]}"
  check_code 201 "$label" >&2
  jget "['orderId']"
}

echo "== 0. normal order is risk-assessed ALLOW"
NORMAL_ORDER=$(create_order "create normal risk-assessed order")
echo "  normal=$NORMAL_ORDER"
if wait_event RiskAssessmentResult "$NORMAL_ORDER"; then ok "RiskAssessmentResult for normal order"; else bad "missing RiskAssessmentResult for normal order"; fi

echo "== 1. high-frequency order triggers risk block"
SECOND_ORDER=$(create_order "create second order before threshold")
echo "  second=$SECOND_ORDER"
BLOCKED_ORDER=$(create_order "create high-frequency order")
echo "  blockedCandidate=$BLOCKED_ORDER"
if wait_event RiskBlockApplied "$BLOCKED_ORDER"; then ok "RiskBlockApplied for high-frequency order"; else bad "missing RiskBlockApplied for high-frequency order"; fi
sleep 5
req GET journey-order "/api/v1/journey-orders/$BLOCKED_ORDER"
BLOCKED_STATUS=$(jget "['status']")
echo "  blocked order status=$BLOCKED_STATUS [$LAST_CODE]"
[ "$BLOCKED_STATUS" = "CANCELLED" ] && ok "RiskBlockApplied cancelled order" || bad "blocked order not CANCELLED ($BLOCKED_STATUS)"

echo "== 2. lift block and create fresh order with same account"
req POST risk-compliance /api/v1/risk-blocks/lift "{\"subjectRef\":\"$BLOCKED_ORDER\",\"scope\":\"ORDER\",\"reasonCode\":\"MANUAL_REVIEW_CLEARED\"}"
check_code 201 "lift risk block"
if wait_event RiskBlockLifted "$BLOCKED_ORDER"; then ok "RiskBlockLifted published"; else bad "missing RiskBlockLifted"; fi

LIFTED_ORDER=$(create_order "create order after lift with same account")
echo "  liftedSameAccount=$LIFTED_ORDER account=$ACCT"
if wait_event RiskAssessmentResult "$LIFTED_ORDER"; then ok "RiskAssessmentResult for post-lift order"; else bad "missing RiskAssessmentResult after lift"; fi
sleep 3
req GET journey-order "/api/v1/journey-orders/$LIFTED_ORDER"
LIFTED_STATUS=$(jget "['status']")
echo "  post-lift order status=$LIFTED_STATUS [$LAST_CODE]"
[ "$LIFTED_STATUS" = "CREATED" ] && ok "post-lift same-account order not blocked" || bad "post-lift same-account order unexpected status ($LIFTED_STATUS)"

summary
