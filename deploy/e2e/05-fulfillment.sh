#!/usr/bin/env bash
# Fulfillment chain: purchase -> boarding -> completion, plus no-show branch.
cd "$(dirname "$0")" && . ./lib.sh

./02-purchase.sh
. ./.refs.env
ensure_curl_pod
SEG_FULFILL=${SEG_FROM_SEARCH:-$SEG}

find_event() {
  local stream=$1 event_type=$2 field=$3 value=$4 count=${5:-80}
  k exec "$(redis_pod)" -- redis-cli --no-raw XREVRANGE "$stream" + - COUNT "$count" 2>/dev/null > /tmp/e2e-events.txt
  EVENT_TYPE="$event_type" FIELD="$field" VALUE="$value" python3 - << 'PY'
import json, os, re
raw = open('/tmp/e2e-events.txt').read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e = json.loads(m.group(0).encode().decode('unicode_escape'))
    except Exception:
        continue
    if e.get('eventType') == os.environ['EVENT_TYPE'] and e.get('payload', {}).get(os.environ['FIELD']) == os.environ['VALUE']:
        print(json.dumps(e)); break
PY
}

event_count() { xlen "$1" 2>/dev/null || echo 0; }

FULFILLMENT_BEFORE=$(event_count events:fulfillment)
REPORTING_BEFORE=""
req GET reporting /api/v1/dashboards/dash-revenue
if [ "$LAST_CODE" = 200 ]; then
  REPORTING_BEFORE=$(jget "['currentSnapshot']['eventCount']" || true)
fi

echo "== 1. verify boarding for purchased entitlement"
BOARD_AT="2026-08-01T08:00:00Z"
req POST fulfillment /api/v1/fulfillment-records/boarding "{\"entitlementId\":\"$ENT\",\"segmentBookingId\":\"$SB\",\"journeyOrderId\":\"$ORDER\",\"travelerId\":\"$TVL\",\"segmentRef\":\"$SEG_FULFILL\",\"source\":\"GATE\",\"sourceEventId\":\"gate-$(uuid7)\",\"occurredAt\":\"$BOARD_AT\"}"
check_code 201 "verify boarding"
FR=$(jget "['fulfillmentRecordId']")
sleep 5
req GET entitlement-ticketing "/api/v1/entitlements/$ENT"
BOARD_STATUS=$(jget "['status']")
[ "$BOARD_STATUS" = "BOARDED" ] && ok "entitlement moved to BOARDED" || bad "entitlement status after boarding is $BOARD_STATUS"
BV=$(find_event events:fulfillment BoardingVerified entitlementId "$ENT")
[ -n "$BV" ] && ok "BoardingVerified fact published" || bad "missing BoardingVerified fact"

echo "== 2. complete segment"
DONE_AT="2026-08-01T13:30:00Z"
req POST fulfillment /api/v1/fulfillment-records/completions "{\"entitlementId\":\"$ENT\",\"segmentBookingId\":\"$SB\",\"journeyOrderId\":\"$ORDER\",\"travelerId\":\"$TVL\",\"segmentRef\":\"$SEG_FULFILL\",\"completionSource\":\"ARRIVAL\",\"completedAt\":\"$DONE_AT\"}"
check_code 201 "segment completed"
sleep 5
req GET entitlement-ticketing "/api/v1/entitlements/$ENT"
FINAL_STATUS=$(jget "['status']")
[ "$FINAL_STATUS" = "BOARDED" ] && ok "entitlement remains BOARDED after completion" || bad "entitlement final status is $FINAL_STATUS"
FC=$(find_event events:fulfillment FulfillmentCompleted entitlementId "$ENT")
[ -n "$FC" ] && ok "FulfillmentCompleted fact published" || bad "missing FulfillmentCompleted fact"
FULFILLMENT_AFTER=$(event_count events:fulfillment)
[ "${FULFILLMENT_AFTER:-0}" -gt "${FULFILLMENT_BEFORE:-0}" ] && ok "events:fulfillment grew" || bad "events:fulfillment did not grow"

sleep 8
req GET reporting /api/v1/dashboards/dash-revenue
if [ "$LAST_CODE" = 200 ]; then
  REPORTING_AFTER=$(jget "['currentSnapshot']['eventCount']" || true)
  if [ -n "$REPORTING_BEFORE" ] && [ -n "$REPORTING_AFTER" ] && [ "$REPORTING_AFTER" != "$REPORTING_BEFORE" ]; then ok "reporting dashboard fact count changed"; else bad "reporting dashboard fact count did not change"; fi
else
  bad "reporting dashboard unreachable [$LAST_CODE]"
fi

echo "== 3. no-show branch on another order"
./02-purchase.sh
. ./.refs.env
SEG_FULFILL=${SEG_FROM_SEARCH:-$SEG}
req POST fulfillment /api/v1/fulfillment-records/no-show "{\"entitlementId\":\"$ENT\",\"segmentBookingId\":\"$SB\",\"journeyOrderId\":\"$ORDER\",\"travelerId\":\"$TVL\",\"segmentRef\":\"$SEG_FULFILL\",\"reason\":\"BOARDING_WINDOW_EXPIRED\"}"
check_code 201 "record no-show"
sleep 5
req GET entitlement-ticketing "/api/v1/entitlements/$ENT"
NS_STATUS=$(jget "['status']")
[ "$NS_STATUS" = "NO_SHOW" ] && ok "entitlement moved to NO_SHOW" || bad "no-show entitlement status is $NS_STATUS"
NS_EVT=$(find_event events:fulfillment NoShowRecorded entitlementId "$ENT")
[ -n "$NS_EVT" ] && ok "NoShowRecorded fact published" || bad "missing NoShowRecorded fact"

summary
