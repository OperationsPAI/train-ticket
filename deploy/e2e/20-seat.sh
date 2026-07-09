#!/usr/bin/env bash
# Seat Assignment e2e: SeatMap CRUD/publish, allocation, adjacency degradation,
# STANDING, release recovery, and published-map immutability.
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

seat_req() { # METHOD PATH BODY -> RESP/LAST_CODE
  local m=$1 p=$2 body=${3:-} out
  out=$(k exec -i e2e-curl -- curl -s -w $'\n%{http_code}' -X "$m" "http://seat-assignment:8080$p" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid7)" ${body:+-d "$body"} 2>/dev/null)
  LAST_CODE=$(echo "$out" | tail -1); RESP=$(echo "$out" | sed '$d')
}

echo "== 20-seat: seat-assignment lifecycle"
SS="ss-$(uuid7)"; DATE="2026-08-02"
seat_req POST /api/v1/seat-maps "{\"scheduledServiceRef\":\"$SS\",\"serviceDate\":\"$DATE\",\"compositionVersion\":\"v1\",\"compositionSeed\":\"SMALL\",\"mappingVersion\":\"sim-v1\",\"changeScenario\":\"SMALL\",\"operatorRef\":\"op-e2e\"}"
check_code 201 "create SeatMap"
SMAP=$(jget "['seatMapId']"); VER=$(jget "['seatMapVersion']"); SU=$(jget "['coaches'][0]['seatUnits'][0]['seatUnitRef']")
seat_req POST "/api/v1/seat-maps/$SMAP/publish" "{\"expectedSeatMapVersion\":$VER,\"publishReason\":\"E2E_READY\",\"operatorRef\":\"op-e2e\"}"
check_code 200 "publish SeatMap"
seat_req POST "/api/v1/seat-maps/$SMAP/seat-units/$SU/mark-unavailable" "{\"expectedSeatMapVersion\":2,\"unavailableReason\":\"MAINTENANCE\",\"operatorRef\":\"op-e2e\"}"
[ "$LAST_CODE" = 422 ] && ok "published SeatMap immutable" || bad "published SeatMap mutation got $LAST_CODE"

alloc_body() { python3 - "$SS" "$DATE" "$1" "$2" "$3" "$4" <<'PY'
import json, sys
ss,date,sb,tvl,hold,pref = sys.argv[1:]
p={"segmentBookingId":sb,"journeyOrderId":"ord-"+__import__('uuid').uuid4().hex[:8]+"-0000-7000-8000-000000000001","travelerRef":tvl,"segmentRef":"seg-"+__import__('uuid').uuid4().hex[:8]+"-0000-7000-8000-000000000002","scheduledServiceRef":ss,"serviceDate":date,"capacityHoldId":hold,"capacityUnitRef":"cap-standard","interval":{"fromSeq":1,"toSeq":3},"classRef":"standard","issuePurpose":"INITIAL","expiresAt":"2026-08-02T08:00:00Z"}
if pref != '-': p['seatPreferences']=json.loads(pref)
print(json.dumps(p))
PY
}
PREF_ADJ='{"acceptStanding":false,"adjacencyPreference":"ADJACENT","adjacencyGroupRef":"grp-e2e","preferenceVersion":"pv1"}'
B1=$(alloc_body "sb-$(uuid7)" "tvl-$(uuid7)" "hold-$(uuid7)" "$PREF_ADJ"); seat_req POST /api/v1/internal/seat-allocations "$B1"; check_code 201 "allocate first adjacent traveler"; A1=$(jget "['seatAllocationId']"); C1=$(jget "['seatRef']['coachNo']")
B2=$(alloc_body "sb-$(uuid7)" "tvl-$(uuid7)" "hold-$(uuid7)" "$PREF_ADJ"); seat_req POST /api/v1/internal/seat-allocations "$B2"; check_code 201 "allocate second adjacent traveler"; C2=$(jget "['seatRef']['coachNo']"); DEG=$(jget "['seatRef']['degraded']")
[ "$C1" = "$C2" ] || [ "$DEG" = True ] || [ "$DEG" = true ] && ok "adjacency satisfied or degradation fact returned" || bad "adjacency neither satisfied nor degraded"
PREF_ST='{"acceptStanding":true,"preferenceVersion":"pv-standing"}'
B3=$(alloc_body "sb-$(uuid7)" "tvl-$(uuid7)" "hold-$(uuid7)" "$PREF_ST"); seat_req POST /api/v1/internal/seat-allocations "$B3"; check_code 201 "full SeatMap returns STANDING"; TYPE=$(jget "['seatRef']['allocationType']"); [ "$TYPE" = STANDING ] && ok "STANDING success seatRef" || bad "expected STANDING got $TYPE"
seat_req GET "/api/v1/seat-allocations/$A1"; HOLD=$(jget "['capacityHoldId']")
k exec "$(redis_pod)" -- redis-cli XADD events:capacity-availability '*' envelope "{\"eventId\":\"evt-$(uuid7)\",\"eventType\":\"CapacityReleased\",\"schemaVersion\":1,\"producer\":\"capacity-availability\",\"correlationId\":\"corr-$(uuid7)\",\"occurredAt\":\"2026-08-02T00:00:00Z\",\"payload\":{\"holdId\":\"$HOLD\",\"capacityUnitRef\":\"cap-standard\",\"interval\":{\"fromSeq\":1,\"toSeq\":3},\"releasedAt\":\"2026-08-02T00:00:00Z\",\"releaseReason\":\"REFUND\"}}" >/dev/null 2>&1 || true
sleep 5
seat_req GET "/api/v1/seat-allocations/$A1"; ST=$(jget "['status']"); [ "$ST" = RELEASED ] && ok "capacity release recovers seat" || bad "release status $ST"
summary
