#!/usr/bin/env bash
# Legacy ACL strangler: complete lifecycle through legacy endpoints only.
cd "$(dirname "$0")" && . ./lib.sh
. ./.refs.env
ensure_curl_pod

legacy_req() {
  local p=$1 body=$2 key=${3:-$(uuid7)} out
  out=$(k exec -i e2e-curl -- curl -s -w $'\n%{http_code}' -X POST "http://legacy-acl:8080$p" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $key" \
    -H 'X-Legacy-Operator: e2e-legacy-operator' \
    -H 'X-Legacy-Reason: E2E_LEGACY_ACL' \
    -d "$body" 2>/dev/null)
  LAST_CODE=$(echo "$out" | tail -1)
  RESP=$(echo "$out" | sed '$d')
}

assert_legacy_response() {
  local expected_status=$1 description=$2
  if [ "$LAST_CODE" = "200" ]; then ok "$description HTTP 200"; else bad "$description HTTP got $LAST_CODE"; fi
  STATUS_FIELD=$(echo "$RESP" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("status", "")); assert "msg" in d and "data" in d' 2>/dev/null || true)
  if [ "$STATUS_FIELD" = "$expected_status" ]; then ok "$description legacy status=$expected_status"; else bad "$description legacy status got $STATUS_FIELD want $expected_status"; fi
}

find_stream_event() {
  local stream=$1 event_type=$2 field=$3 value=$4 count=${5:-120}
  k exec "$(redis_pod)" -- redis-cli --no-raw XREVRANGE "$stream" + - COUNT "$count" 2>/dev/null > /tmp/legacy-acl-events.txt
  EVENT_TYPE="$event_type" FIELD="$field" VALUE="$value" python3 - << 'PY'
import json, os, re
raw = open('/tmp/legacy-acl-events.txt').read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e = json.loads(m.group(0).encode().decode('unicode_escape'))
    except Exception:
        continue
    payload = e.get('payload', {})
    if e.get('eventType') == os.environ['EVENT_TYPE'] and str(payload.get(os.environ['FIELD'], '')) == os.environ['VALUE']:
        print(json.dumps(e)); break
PY
}

wait_legacy_event() {
  local operation=$1 key=$2 found=""
  for attempt in 1 2 3 4 5 6; do
    found=$(find_stream_event events:legacy-acl LegacyCommandMapped sourceRef "$key")
    if [ -n "$found" ] && echo "$found" | OP="$operation" python3 -c 'import json,sys,os; e=json.load(sys.stdin); assert e["payload"]["legacyOperation"] == os.environ["OP"]' 2>/dev/null; then
      break
    fi
    sleep 3
  done
  [ -n "$found" ] && ok "LegacyCommandMapped $operation" || bad "missing LegacyCommandMapped $operation"
}

wait_audit() {
  local key=$1 found=""
  for attempt in 1 2 3 4 5 6; do
    req GET admin-audit "/api/v1/admin/audit-trail?businessRef=$key&limit=20&offset=0"
    found=$(echo "$RESP" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("total", 0))' 2>/dev/null || echo 0)
    [ "${found:-0}" -gt 0 ] 2>/dev/null && break
    sleep 3
  done
  [ "${found:-0}" -gt 0 ] 2>/dev/null && ok "admin-audit recorded $key" || bad "admin-audit missing $key"
}

legacy_step() {
  local op=$1 path=$2 body=$3 key
  key=$(uuid7)
  legacy_req "$path" "$body" "$key"
  # wait_* below overwrite $RESP with admin-audit queries; stash the
  # legacy response so callers can extract data.* from it afterwards.
  LEGACY_RESP="$RESP"
  assert_legacy_response 1 "$op"
  wait_legacy_event "$op" "$key"
  wait_audit "$key"
  RESP="$LEGACY_RESP"
  LEGACY_KEY="$key"
}

ACCT="acc-$(uuid7)"
echo "== 1. preserve through legacy ACL"
legacy_step PRESERVE /api/v1/legacy/preserve "{\"accountId\":\"$ACCT\",\"contactsId\":\"$TVL\",\"tripId\":\"G1234\",\"seatType\":\"SECOND\",\"date\":\"2026-08-01\",\"from\":\"$P_BJ\",\"to\":\"$P_SH\"}"
ORDER=$(jget "['data']['orderId']"); TOTAL=$(jget "['data']['total']['minorUnits']")
echo "  ORDER=$ORDER totalMinor=$TOTAL"

sleep 5
echo "== 2. inside payment"
legacy_step INSIDE_PAYMENT /api/v1/legacy/inside_payment "{\"orderId\":\"$ORDER\",\"price\":{\"currency\":\"CNY\",\"minorUnits\":${TOTAL:-10750}}}"
PI=$(jget "['data']['paymentIntentId']")
echo "  PI=$PI"

sleep 5
echo "== 3. ticket issue"
# Allow booking-orchestration to process reservation confirmation before ticketing.
sleep 8
legacy_step TICKET_ISSUE /api/v1/legacy/ticket_issue "{\"orderId\":\"$ORDER\"}"
ENT=$(jget "['data']['entitlementId']")
echo "  ENT=$ENT"

sleep 5
echo "== 4. execute boarding"
legacy_step EXECUTE /api/v1/legacy/execute "{\"orderId\":\"$ORDER\"}"
FR=$(jget "['data']['fulfillmentRecordId']")
echo "  FR=$FR"

ACCT2="acc-$(uuid7)"
echo "== 5. second order preserve"
legacy_step PRESERVE /api/v1/legacy/preserve "{\"accountId\":\"$ACCT2\",\"contactsId\":\"$TVL\",\"tripId\":\"G1234\",\"seatType\":\"SECOND\",\"date\":\"2026-08-01\",\"from\":\"$P_BJ\",\"to\":\"$P_SH\"}"
ORDER2=$(jget "['data']['orderId']"); TOTAL2=$(jget "['data']['total']['minorUnits']")
legacy_step INSIDE_PAYMENT /api/v1/legacy/inside_payment "{\"orderId\":\"$ORDER2\",\"price\":{\"currency\":\"CNY\",\"minorUnits\":${TOTAL2:-10750}}}"
sleep 8
legacy_step TICKET_ISSUE /api/v1/legacy/ticket_issue "{\"orderId\":\"$ORDER2\"}"
sleep 5

legacy_step CANCEL /api/v1/legacy/cancel "{\"orderId\":\"$ORDER2\"}"
REFUNDABLE=$(jget "['data']['refundAmount']['minorUnits']")
[ "$REFUNDABLE" = "8750" ] && ok "legacy cancel refundable amount is 8750" || bad "legacy cancel refundable amount $REFUNDABLE, expected 8750"

ACCT3="acc-$(uuid7)"
echo "== 6. rebook creates complete replacement itinerary"
legacy_step PRESERVE /api/v1/legacy/preserve "{\"accountId\":\"$ACCT3\",\"contactsId\":\"$TVL\",\"tripId\":\"G1234\",\"seatType\":\"SECOND\",\"date\":\"2026-08-01\",\"from\":\"$P_BJ\",\"to\":\"$P_SH\"}"
ORDER3=$(jget "['data']['orderId']"); TOTAL3=$(jget "['data']['total']['minorUnits']")
legacy_step INSIDE_PAYMENT /api/v1/legacy/inside_payment "{\"orderId\":\"$ORDER3\",\"price\":{\"currency\":\"CNY\",\"minorUnits\":${TOTAL3:-10750}}}"
sleep 8
legacy_step TICKET_ISSUE /api/v1/legacy/ticket_issue "{\"orderId\":\"$ORDER3\"}"
sleep 5
legacy_step REBOOK /api/v1/legacy/rebook "{\"orderId\":\"$ORDER3\",\"date\":\"2026-08-01\",\"seatType\":\"SECOND\",\"from\":\"$P_BJ\",\"to\":\"$P_SH\"}"
REBOOK_ORDER=$(jget "['data']['replacementOrderId']")
REBOOK_SEGMENTS=$(jget "['data']['rebookedSegmentCount']")
REBOOK_BOOKINGS=$(echo "$RESP" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d.get("data", {}).get("replacementSegmentBookingIds", [])))' 2>/dev/null || echo 0)
[ -n "$REBOOK_ORDER" ] && ok "legacy rebook replacement order returned" || bad "legacy rebook replacement order missing"
[ "${REBOOK_SEGMENTS:-0}" -ge 1 ] && ok "legacy rebook replacement segment count returned" || bad "legacy rebook replacement segment count missing"
[ "${REBOOK_BOOKINGS:-0}" -ge "${REBOOK_SEGMENTS:-1}" ] && ok "legacy rebook reserved every returned segment" || bad "legacy rebook reserved $REBOOK_BOOKINGS of $REBOOK_SEGMENTS segments"

summary
