#!/usr/bin/env bash
# ManualAction closure: customer-service request -> admin-audit approval/execution -> customer-service timeline.
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

if [ ! -f ./.refs.env ] || ! grep -q '^ORDER=' ./.refs.env; then
  echo "== prerequisite purchase refs missing; running 02-purchase.sh"
  bash ./02-purchase.sh
fi
. ./.refs.env

retry() {
  local description=$1 command=$2 attempts=${3:-4} delay=${4:-5}
  for attempt in $(seq 1 "$attempts"); do
    if eval "$command"; then ok "$description"; return 0; fi
    sleep "$delay"
  done
  bad "$description"
  return 1
}

stream_has_event() {
  local stream=$1 event_type=$2 manual_action_id=$3
  k exec "$(redis_pod)" -- redis-cli --no-raw XREVRANGE "$stream" + - COUNT 50 > /tmp/manual-action-stream.txt
  EVENT_TYPE="$event_type" MANUAL_ACTION_ID="$manual_action_id" python3 - <<'PY'
import json, os, re, sys
raw = open('/tmp/manual-action-stream.txt').read()
for match in re.finditer(r'\{.*\}', raw):
    try:
        envelope = json.loads(match.group(0).encode().decode('unicode_escape'))
    except Exception:
        continue
    if envelope.get('eventType') == os.environ['EVENT_TYPE'] and envelope.get('payload', {}).get('manualActionId') == os.environ['MANUAL_ACTION_ID']:
        sys.exit(0)
sys.exit(1)
PY
}

timeline_has_manual_result() {
  req GET customer-service "/api/v1/support-cases/$CASE"
  [ "$LAST_CODE" = 200 ] || return 1
  tmp=/tmp/manual-action-case.json
  printf '%s' "$RESP" > "$tmp"
  MANUAL_ACTION_ID="$MA" python3 - <<'PY'
import json, os, sys
with open('/tmp/manual-action-case.json') as f:
    case = json.load(f)
for entry in case.get('timeline', []):
    if entry.get('eventType') == 'ManualActionResultRecorded' and entry.get('payload', {}).get('manualActionId') == os.environ['MANUAL_ACTION_ID']:
        sys.exit(0)
sys.exit(1)
PY
}

echo "== 1. open support case for purchased order"
req POST customer-service /api/v1/support-cases "{\"requesterRef\":\"${TVL:-tvl-manual}\",\"channel\":\"APP\",\"priority\":\"NORMAL\",\"description\":\"Manual refund review\",\"businessReferences\":{\"journeyOrderId\":\"$ORDER\"}}"
check_code 201 "open support case"
CASE=$(jget "['caseId']")
echo "  CASE=$CASE"

echo "== 2. request manual action through customer-service"
req POST customer-service "/api/v1/support-cases/$CASE/manual-action-requests" "{\"targetDomain\":\"post-sales\",\"commandType\":\"ManualRefundReviewRequested\",\"operatorRef\":\"op-manual-requester\",\"reason\":\"CUSTOMER_REQUEST\",\"description\":\"Review refund eligibility\",\"requiresApproval\":true}"
[ "$LAST_CODE" = 202 ] && ok "request manual action [$LAST_CODE]" || bad "request manual action [got $LAST_CODE want 202]"
MA=$(jget "['manualActionId']")
echo "  MA=$MA"

retry "admin-audit consumed pending request" "stream_has_event events:customer-service ManualActionRequested '$MA' && k exec \$(redis_pod) -- redis-cli --no-raw XREVRANGE events:admin-audit + - COUNT 50 >/tmp/admin-audit-stream.txt && grep -q '$MA' /tmp/admin-audit-stream.txt" 4 5

echo "== 3. register approver and approve (admin-audit records execution fact in phase 1)"
# Unique email per run: a fixed address 409s on suite reruns within one
# admin-audit pod lifetime.
APPROVER_EMAIL="manual-approver-$(uuid7)@example.com"
req POST admin-audit /api/v1/admin/operators "{\"email\":\"$APPROVER_EMAIL\",\"role\":\"ADMIN\",\"scopes\":[\"OPERATOR_WRITE\",\"AUDIT_READ\"],\"displayName\":\"Manual Approver\"}"
check_code 201 "register admin-audit approver"
APPROVER=$(jget "['operatorId']")
req POST admin-audit "/api/v1/admin/manual-actions/$MA/approve" "{\"approvedByOperatorId\":\"$APPROVER\"}"
[ "$LAST_CODE" = 200 ] && ok "approve manual action [$LAST_CODE]" || bad "approve manual action [got $LAST_CODE want 200]"

retry "ManualActionExecuted published" "stream_has_event events:admin-audit ManualActionExecuted '$MA'" 4 5
retry "customer-service timeline records ManualActionResultRecorded" "timeline_has_manual_result" 4 5

summary
