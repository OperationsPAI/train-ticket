#!/usr/bin/env bash
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

echo "== 19-transfer: transfer management protected/self-transfer loop"

iso() { python3 - "$1" <<'PY'
import sys, datetime
print((datetime.datetime.now(datetime.UTC)+datetime.timedelta(minutes=int(sys.argv[1]))).isoformat(timespec='seconds').replace('+00:00','Z'))
PY
}

json_get() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

req POST transfer-management /api/v1/mct-rules "{\"fromNodeType\":\"STATION\",\"toNodeType\":\"STATION\",\"transferCategory\":\"SAME_STATION\",\"minimumMinutes\":20,\"conditions\":{},\"validFrom\":\"2025-01-01T00:00:00Z\"}"
check_code 201 "create mct rule"
MCT=$(jget "['mctRuleId']")
req POST transfer-management "/api/v1/mct-rules/$MCT/publish" "{\"publishedBy\":{\"actorType\":\"OPERATIONS\",\"actorId\":\"ops-e2e\"},\"publishReason\":\"e2e\"}"
check_code 200 "publish mct rule"
req PATCH transfer-management "/api/v1/mct-rules/$MCT" "{\"minimumMinutes\":25}"
check_code 412 "published mct immutable"

RUN=$(uuid7 | tail -c 13)

create_connection() {
  local typ=$1 suffix=$2
  req POST transfer-management /api/v1/transfer-plans "{\"itineraryRef\":\"iti-tm-$suffix\",\"planningSnapshotVersion\":1,\"journeyOrderId\":\"jo-tm-$suffix\",\"travelerRefs\":[\"trav-$suffix\"]}"
  check_code 201 "create transfer plan $typ"
  local plan; plan=$(jget "['transferPlanId']")
  req POST transfer-management /api/v1/connections "{\"transferPlanId\":\"$plan\",\"itineraryRef\":\"iti-tm-$suffix\",\"journeyOrderId\":\"jo-tm-$suffix\",\"previousSegmentRef\":\"seg-prev-$suffix\",\"nextSegmentRef\":\"seg-next-$suffix\",\"travelerRefs\":[\"trav-$suffix\"],\"fromNodeRef\":\"sta-a\",\"toNodeRef\":\"sta-a\",\"fromNodeType\":\"STATION\",\"toNodeType\":\"STATION\",\"transferCategory\":\"SAME_STATION\",\"contractId\":\"cct-$suffix\",\"contractType\":\"$typ\",\"window\":{\"plannedArrivalAt\":\"$(iso 0)\",\"nextDepartureAt\":\"$(iso 60)\",\"nextCutoffAt\":\"$(iso 50)\"}}"
  check_code 201 "register connection $typ"
  jget "['connectionId']"
}

miss_connection() {
  local suffix=$1
  req POST transfer-management /api/v1/segment-status-reports "{\"segmentRef\":\"seg-prev-$suffix\",\"reportType\":\"DELAY\",\"reportedBy\":{\"actorType\":\"SYSTEM\",\"actorId\":\"ops\"},\"sourceSystem\":\"OPERATIONS\",\"sourceRecordId\":\"delay-risk-$suffix\",\"observedAt\":\"$(iso 1)\",\"estimatedArrivalAt\":\"$(iso 40)\"}"
  check_code 202 "delay makes at-risk $suffix"
  STATUS=$(echo "$RESP" | json_get 'd["updatedConnections"][0]["status"]')
  [ "$STATUS" = "AT_RISK" ] && ok "connection at-risk $suffix" || bad "connection not at-risk ($STATUS) $suffix"
  req POST transfer-management /api/v1/segment-status-reports "{\"segmentRef\":\"seg-prev-$suffix\",\"reportType\":\"DELAY\",\"reportedBy\":{\"actorType\":\"SYSTEM\",\"actorId\":\"ops\"},\"sourceSystem\":\"OPERATIONS\",\"sourceRecordId\":\"delay-missed-$suffix\",\"observedAt\":\"$(iso 2)\",\"estimatedArrivalAt\":\"$(iso 80)\"}"
  check_code 202 "delay makes missed $suffix"
  CASE=$(echo "$RESP" | json_get '((d["updatedConnections"][0].get("recovery") or {}).get("caseIds",[""])[0])')
  [ -n "$CASE" ] && ok "disruption recovery case opened $CASE" || bad "missing recovery case $suffix"
}

CON=$(create_connection PROTECTED "prot-$RUN" | tail -1)
miss_connection "prot-$RUN"
req GET disruption-recovery "/api/v1/recovery-cases/$CASE"
check_code 200 "real disruption recovery case exists"
CASE_STATUS=$(echo "$RESP" | json_get 'd["status"]')
[ "$CASE_STATUS" = "AWAITING_USER_CHOICE" ] && ok "missed connection awaits choice" || bad "case status $CASE_STATUS"
TYPES=$(echo "$RESP" | json_get '" ".join(sorted(o["optionType"] for o in d["optionSet"]["options"]))')
[ "$TYPES" = "REACCOMMODATION WAIT" ] && ok "WAIT plus REACCOMMODATION options" || bad "unexpected options $TYPES"
REACC=$(echo "$RESP" | json_get 'next(o["optionId"] for o in d["optionSet"]["options"] if o["optionType"]=="REACCOMMODATION")')
req POST disruption-recovery "/api/v1/recovery-cases/$CASE/select-option" "{\"optionId\":\"$REACC\",\"selectedBy\":{\"actorType\":\"USER\",\"actorId\":\"e2e-user\"}}"
check_code 200 "select reaccommodation"
sleep 2
req GET transfer-management "/api/v1/connections/$CON"
check_code 200 "get reaccommodated connection"
STATUS=$(jget "['status']")
REPL=$(jget ".get('replacementConnectionId','')")
[ "$STATUS" = "RECOVERED" ] && ok "original connection recovered" || bad "connection status $STATUS"
[ -n "$REPL" ] && ok "replacement connection recorded" || bad "missing replacementConnectionId"
req GET transfer-management "/api/v1/connections/$REPL"
check_code 200 "replacement connection exists"
ORIG=$(jget ".get('replacementOfConnectionId','')")
[ "$ORIG" = "$CON" ] && ok "replacement points at original" || bad "replacement original $ORIG"

CON_WAIT=$(create_connection PROTECTED "wait-$RUN" | tail -1)
miss_connection "wait-$RUN"
req GET disruption-recovery "/api/v1/recovery-cases/$CASE"
check_code 200 "wait branch case exists"
WAIT=$(echo "$RESP" | json_get 'next(o["optionId"] for o in d["optionSet"]["options"] if o["optionType"]=="WAIT")')
req POST disruption-recovery "/api/v1/recovery-cases/$CASE/select-option" "{\"optionId\":\"$WAIT\",\"selectedBy\":{\"actorType\":\"USER\",\"actorId\":\"e2e-user\"}}"
check_code 200 "select wait branch"
sleep 2
req GET transfer-management "/api/v1/connections/$CON_WAIT"
check_code 200 "get wait recovered connection"
STATUS=$(jget "['status']")
[ "$STATUS" = "RECOVERED" ] && ok "wait branch recovered" || bad "wait branch status $STATUS"

EVENT_CON=$(create_connection PLATFORM_ASSISTED "event-$RUN" | tail -1)
req POST fulfillment /api/v1/segment-status "{\"segmentRef\":\"seg-prev-event-$RUN\",\"scheduledServiceRef\":\"svc-event-$RUN\",\"serviceDate\":\"$(date -u +%F)\",\"status\":\"DELAY\",\"sourceSystem\":\"OPS\",\"observedAt\":\"$(iso 1)\",\"estimatedArrivalAt\":\"$(iso 40)\"}"
check_code 201 "fulfillment segment delay declaration"
sleep 3
req GET transfer-management "/api/v1/connections/$EVENT_CON"
check_code 200 "get event-driven at-risk connection"
STATUS=$(jget "['status']")
[ "$STATUS" = "AT_RISK" ] && ok "fulfillment event made connection at-risk" || bad "event-driven status $STATUS"

SELF=$(create_connection SELF_TRANSFER "self-$RUN" | tail -1)
req POST transfer-management /api/v1/segment-status-reports "{\"segmentRef\":\"seg-next-self-$RUN\",\"reportType\":\"CANCELLED\",\"reportedBy\":{\"actorType\":\"SYSTEM\",\"actorId\":\"ops\"},\"sourceSystem\":\"OPERATIONS\",\"sourceRecordId\":\"cancel-self-$RUN\",\"observedAt\":\"$(iso 1)\",\"cancelledAt\":\"$(iso 1)\"}"
check_code 202 "self-transfer missed"
HAS=$(echo "$RESP" | json_get '"recovery" in d["updatedConnections"][0]')
[ "$HAS" = "False" ] && ok "self-transfer did not open case" || bad "self-transfer opened recovery"

summary
