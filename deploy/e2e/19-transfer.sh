#!/usr/bin/env bash
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

echo "== 19-transfer: transfer management protected/self-transfer loop"

iso() { python3 - "$1" <<'PY'
import sys, datetime
print((datetime.datetime.now(datetime.UTC)+datetime.timedelta(minutes=int(sys.argv[1]))).isoformat(timespec='seconds').replace('+00:00','Z'))
PY
}

req POST transfer-management /api/v1/mct-rules "{\"fromNodeType\":\"STATION\",\"toNodeType\":\"STATION\",\"transferCategory\":\"SAME_STATION\",\"minimumMinutes\":20,\"conditions\":{},\"validFrom\":\"2025-01-01T00:00:00Z\"}"
check_code 201 "create mct rule"
MCT=$(jget "['mctRuleId']")
req POST transfer-management "/api/v1/mct-rules/$MCT/publish" "{\"publishedBy\":{\"actorType\":\"OPERATIONS\",\"actorId\":\"ops-e2e\"},\"publishReason\":\"e2e\"}"
check_code 200 "publish mct rule"
req PATCH transfer-management "/api/v1/mct-rules/$MCT" "{\"minimumMinutes\":25}"
check_code 412 "published mct immutable"

create_connection() {
  local typ=$1 suffix=$2
  req POST transfer-management /api/v1/transfer-plans "{\"itineraryRef\":\"iti-tm-$suffix\",\"planningSnapshotVersion\":1,\"journeyOrderId\":\"jo-tm-$suffix\",\"travelerRefs\":[\"trav-$suffix\"]}"
  check_code 201 "create transfer plan $typ"
  local plan; plan=$(jget "['transferPlanId']")
  req POST transfer-management /api/v1/connections "{\"transferPlanId\":\"$plan\",\"itineraryRef\":\"iti-tm-$suffix\",\"journeyOrderId\":\"jo-tm-$suffix\",\"previousSegmentRef\":\"seg-prev-$suffix\",\"nextSegmentRef\":\"seg-next-$suffix\",\"travelerRefs\":[\"trav-$suffix\"],\"fromNodeRef\":\"sta-a\",\"toNodeRef\":\"sta-a\",\"fromNodeType\":\"STATION\",\"toNodeType\":\"STATION\",\"transferCategory\":\"SAME_STATION\",\"contractId\":\"cct-$suffix\",\"contractType\":\"$typ\",\"window\":{\"plannedArrivalAt\":\"$(iso 0)\",\"nextDepartureAt\":\"$(iso 60)\",\"nextCutoffAt\":\"$(iso 50)\"}}"
  check_code 201 "register connection $typ"
  jget "['connectionId']"
}

CON=$(create_connection PROTECTED prot | tail -1)
req POST transfer-management /api/v1/segment-status-reports "{\"segmentRef\":\"seg-prev-prot\",\"reportType\":\"DELAY\",\"reportedBy\":{\"actorType\":\"SYSTEM\",\"actorId\":\"ops\"},\"sourceSystem\":\"OPERATIONS\",\"sourceRecordId\":\"delay-risk\",\"observedAt\":\"$(iso 1)\",\"estimatedArrivalAt\":\"$(iso 40)\"}"
check_code 202 "delay makes at-risk"
STATUS=$(echo "$RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["updatedConnections"][0]["status"])')
[ "$STATUS" = "AT_RISK" ] && ok "connection at-risk" || bad "connection not at-risk ($STATUS)"
req POST transfer-management /api/v1/segment-status-reports "{\"segmentRef\":\"seg-prev-prot\",\"reportType\":\"DELAY\",\"reportedBy\":{\"actorType\":\"SYSTEM\",\"actorId\":\"ops\"},\"sourceSystem\":\"OPERATIONS\",\"sourceRecordId\":\"delay-missed\",\"observedAt\":\"$(iso 2)\",\"estimatedArrivalAt\":\"$(iso 80)\"}"
check_code 202 "delay makes missed"
CASE=$(echo "$RESP" | python3 -c 'import sys,json; c=json.load(sys.stdin)["updatedConnections"][0]; print((c.get("recovery") or {}).get("caseIds",[""])[0])')
[ -n "$CASE" ] && ok "disruption recovery case opened $CASE" || bad "missing recovery case"
req GET disruption-recovery "/api/v1/recovery-cases/$CASE"
check_code 200 "real disruption recovery case exists"
OPT=$(echo "$RESP" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["optionSet"]["options"][0]["optionId"])')
req POST disruption-recovery "/api/v1/recovery-cases/$CASE/select-option" "{\"optionId\":\"$OPT\",\"selectedBy\":{\"actorType\":\"USER\",\"actorId\":\"trav-e2e\"}}"
check_code 200 "select WAIT recovery"
sleep 2
req GET transfer-management "/api/v1/connections/$CON"
check_code 200 "get recovered connection"
STATUS=$(jget "['status']")
[ "$STATUS" = "RECOVERED" ] && ok "connection recovered" || bad "connection status $STATUS"

SELF=$(create_connection SELF_TRANSFER self | tail -1)
req POST transfer-management /api/v1/segment-status-reports "{\"segmentRef\":\"seg-next-self\",\"reportType\":\"CANCELLED\",\"reportedBy\":{\"actorType\":\"SYSTEM\",\"actorId\":\"ops\"},\"sourceSystem\":\"OPERATIONS\",\"sourceRecordId\":\"cancel-self\",\"observedAt\":\"$(iso 1)\",\"cancelledAt\":\"$(iso 1)\"}"
check_code 202 "self-transfer missed"
HAS=$(echo "$RESP" | python3 -c 'import sys,json; print("recovery" in json.load(sys.stdin)["updatedConnections"][0])')
[ "$HAS" = "False" ] && ok "self-transfer did not open case" || bad "self-transfer opened recovery"

summary
