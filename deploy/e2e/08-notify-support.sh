#!/usr/bin/env bash
# Notification + Customer Service closure: assert purchase emits NotificationDelivered,
# open a support case for that order, run a refund through evaluate+approve, then
# verify the PostSalesApplied fact is attached to the support timeline and resolving
# the case publishes SupportCaseResolved.
cd "$(dirname "$0")" && . ./lib.sh
[ -f ./.refs.env ] || ./02-purchase.sh
. ./.refs.env
ensure_curl_pod

stream_mentions() { # STREAM EVENT_TYPE NEEDLE -> yes/empty
  k exec "$(redis_pod)" -- redis-cli XREVRANGE "$1" + - COUNT 120 > /tmp/notify-support-stream.txt 2>/dev/null
  ET="$2" NEEDLE="$3" python3 - << 'PYEX'
import re, os, json
raw = open("/tmp/notify-support-stream.txt").read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e = json.loads(m.group(0).encode().decode('unicode_escape'))
        if e.get("eventType") == os.environ["ET"] and os.environ["NEEDLE"] in json.dumps(e.get("payload", {})):
            print("yes"); break
    except Exception:
        pass
PYEX
}

notification_delivered_for_recipient() { # RECIPIENT_REF -> yes/empty
  k exec "$(redis_pod)" -- redis-cli XREVRANGE events:notification + - COUNT 240 > /tmp/notify-support-notification.txt 2>/dev/null
  RECIPIENT="$1" python3 - << 'PYEX'
import re, os, json
raw = open("/tmp/notify-support-notification.txt").read()
recipient = os.environ["RECIPIENT"]
events = []
for m in re.finditer(r'\{.*\}', raw):
    try:
        events.append(json.loads(m.group(0).encode().decode('unicode_escape')))
    except Exception:
        pass
scheduled = set()
for e in events:
    payload = e.get("payload", {})
    task_id = payload.get("notificationTaskId")
    if e.get("eventType") == "NotificationScheduled" and payload.get("recipientRef") == recipient and isinstance(task_id, str):
        scheduled.add(task_id)
for e in events:
    payload = e.get("payload", {})
    if e.get("eventType") == "NotificationDelivered" and payload.get("notificationTaskId") in scheduled:
        print("yes")
        break
PYEX
}

echo "== 1. purchase notification delivery"
FOUND=""
for attempt in 1 2 3 4 5; do
  FOUND=$(notification_delivered_for_recipient "$TVL")
  [ "$FOUND" = "yes" ] && break
  sleep 3
done
[ "$FOUND" = "yes" ] && ok "NotificationDelivered published after purchase recipient $TVL" || bad "no NotificationDelivered observed for recipient $TVL"

echo "== 2. open support case referencing order $ORDER"
req POST customer-service /api/v1/support-cases "{\"requesterRef\":\"$TVL\",\"channel\":\"APP\",\"classification\":\"POST_SALES_HELP\",\"priority\":\"NORMAL\",\"description\":\"Help me with my order\",\"businessReferences\":{\"journeyOrderId\":\"$ORDER\"}}"
check_code 201 "open support case"
SUPPORT_CASE=$(jget "['caseId']")
echo "  SUPPORT_CASE=$SUPPORT_CASE"

if [ -z "${CASE:-}" ]; then
  echo "== 3. create and apply refund fact for support timeline"
  req POST post-sales /api/v1/post-sales-cases "{\"journeyOrderId\":\"$ORDER\",\"caseType\":\"REFUND\",\"scope\":{\"orderItemRefs\":[\"$SB\"],\"segmentRefs\":[\"$SEG\"],\"travelerRefs\":[\"$TVL\"],\"entitlementRefs\":[\"$ENT\"]},\"reasonCode\":\"CUSTOMER_REQUEST\",\"actorRef\":\"$ACCT\"}"
  check_code 201 "open post-sales case"
  CASE=$(jget "['caseId']")
else
  echo "== 3. reuse refund case $CASE"
fi
req POST post-sales "/api/v1/post-sales-cases/$CASE/evaluate" '{}'
check_code 200 "evaluate post-sales case"
req POST post-sales "/api/v1/post-sales-cases/$CASE/approve" '{}'
check_code 200 "approve post-sales case"
sleep 8

req GET customer-service "/api/v1/support-cases/$SUPPORT_CASE"
TIMELINE_OK=$(echo "$RESP" | CASE="$CASE" python3 - << 'PYEX'
import sys, json, os
try:
    d=json.load(sys.stdin)
    entries=d.get('timeline', [])
    needle=os.environ['CASE']
    print('yes' if any(e.get('eventType') == 'PostSalesApplied' and needle in json.dumps(e) for e in entries) else '')
except Exception:
    print('')
PYEX
)
[ "$TIMELINE_OK" = "yes" ] && ok "support timeline attached PostSalesApplied refund fact" || bad "timeline missing PostSalesApplied refund fact"

echo "== 4. resolve support case"
req POST customer-service "/api/v1/support-cases/$SUPPORT_CASE/assign" "{\"ownerQueue\":\"tier1\"}"
check_code 200 "assign support case"
req POST customer-service "/api/v1/support-cases/$SUPPORT_CASE/resolve" "{\"summary\":\"Refund evidence reviewed\",\"resolutionCode\":\"POST_SALES_EXPLAINED\"}"
check_code 200 "resolve support case"
sleep 2
RESOLVED=$(stream_mentions events:customer-service SupportCaseResolved "$SUPPORT_CASE")
[ "$RESOLVED" = "yes" ] && ok "SupportCaseResolved published" || bad "SupportCaseResolved missing"

summary
