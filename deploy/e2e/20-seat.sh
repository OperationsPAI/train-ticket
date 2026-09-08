#!/usr/bin/env bash
# Seat Assignment e2e: SeatMap CRUD/publish, allocation, adjacency degradation,
# STANDING, release recovery, and published-map immutability.
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

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

seed_segment() { # service-number -> sets SEEDED_SS/SEEDED_SEG
  local svc=$1 ss seg
  req POST service-plan /api/v1/scheduled-services "{\"carrierId\":\"car-$(uuid7)\",\"serviceNumber\":\"$svc\",\"departureTime\":\"${JOURNEY_DATE}T09:00:00Z\",\"arrivalTime\":\"${JOURNEY_DATE}T14:30:00Z\",\"originNodeId\":\"$WL_N_A\",\"destinationNodeId\":\"$WL_N_B\"}"
  check_code 201 "create waitlist scheduled service $svc"
  ss=$(jget "['scheduledServiceRef']")
  req POST service-plan /api/v1/service-segments "{\"scheduledServiceRef\":\"$ss\",\"originStopRef\":\"$WL_N_A\",\"destinationStopRef\":\"$WL_N_B\",\"departureTime\":\"${JOURNEY_DATE}T09:00:00Z\",\"arrivalTime\":\"${JOURNEY_DATE}T14:30:00Z\"}"
  check_code 201 "create waitlist segment $svc"
  seg=$(jget "['segmentRef']")
  sleep 3
  SEEDED_SS=$ss
  SEEDED_SEG=$seg
}


seat_req() { # METHOD PATH BODY -> RESP/LAST_CODE
  local m=$1 p=$2 body=${3:-} out
  out=$(k exec -i e2e-curl -- curl -s -w $'\n%{http_code}' -X "$m" "http://seat-assignment:8080$p" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid7)" ${body:+-d "$body"} 2>/dev/null)
  LAST_CODE=$(echo "$out" | tail -1); RESP=$(echo "$out" | sed '$d')
}

echo "== 20-seat: seat-assignment lifecycle"
# A seat map / allocation is keyed on a FUTURE service date, and the hold
# expiry below is gated by the real clock (SeatAssignment.IsActive compares
# ExpiresAt.After(now)), so both must be relative.
SS="ss-$(uuid7)"; DATE="$JOURNEY_DATE"
seat_req POST /api/v1/seat-maps "{\"scheduledServiceRef\":\"$SS\",\"serviceDate\":\"$DATE\",\"compositionVersion\":\"v1\",\"compositionSeed\":\"SMALL\",\"mappingVersion\":\"sim-v1\",\"changeScenario\":\"SMALL\",\"operatorRef\":\"op-e2e\"}"
check_code 201 "create SeatMap"
SMAP=$(jget "['seatMapId']"); VER=$(jget "['seatMapVersion']"); SU=$(jget "['coaches'][0]['seatUnits'][0]['seatUnitRef']")
seat_req POST "/api/v1/seat-maps/$SMAP/publish" "{\"expectedSeatMapVersion\":$VER,\"publishReason\":\"E2E_READY\",\"operatorRef\":\"op-e2e\"}"
check_code 200 "publish SeatMap"
seat_req POST "/api/v1/seat-maps/$SMAP/seat-units/$SU/mark-unavailable" "{\"expectedSeatMapVersion\":2,\"unavailableReason\":\"MAINTENANCE\",\"operatorRef\":\"op-e2e\"}"
[ "$LAST_CODE" = 422 ] && ok "published SeatMap immutable" || bad "published SeatMap mutation got $LAST_CODE"

# alloc_body SB TVL HOLD PREF [UNIT_REF FROM_SEQ TO_SEQ]
#
# The last three are optional and default to the synthetic values the earlier
# assertions use, where the hold is fabricated and nothing downstream has to
# match it.
#
# They MUST be passed for anything that then exercises capacity recovery.
# seat-assignment finds affected allocations with
#   WHERE capacityHoldId = ? AND capacityUnitRef = ? AND fromSeq = ? AND toSeq = ?
# (FindSeatAllocationsByCapacityRecovery), so all four have to agree with the
# CapacityReleased event capacity-availability publishes. This test previously
# sent capacityUnitRef="cap-standard" and interval 1..3 even against a real
# hold, while capacity-availability had allocated e.g. unit "05B" over 0..1 --
# so the release matched no allocation, transitionAllocationsByCapacityRecovery
# returned without doing anything, and the seat stayed STANDING. Nothing logged
# it: that function's guard clause returns nil on a non-match.
alloc_body() { python3 - "$SS" "$DATE" "$1" "$2" "$3" "$4" "${5:-cap-standard}" "${6:-1}" "${7:-3}" <<'PY'
import json, sys
ss,date,sb,tvl,hold,pref,unit,from_seq,to_seq = sys.argv[1:]
# Hold until shortly before the 09:00 departure, as the original fixture did.
p={"segmentBookingId":sb,"journeyOrderId":"ord-"+__import__('uuid').uuid4().hex[:8]+"-0000-7000-8000-000000000001","travelerRef":tvl,"segmentRef":"seg-"+__import__('uuid').uuid4().hex[:8]+"-0000-7000-8000-000000000002","scheduledServiceRef":ss,"serviceDate":date,"capacityHoldId":hold,"capacityUnitRef":unit,"interval":{"fromSeq":int(from_seq),"toSeq":int(to_seq)},"classRef":"standard","issuePurpose":"INITIAL","expiresAt":date+"T08:00:00Z"}
if pref != '-': p['seatPreferences']=json.loads(pref)
print(json.dumps(p))
PY
}
PREF_ADJ='{"acceptStanding":false,"adjacencyPreference":"ADJACENT","adjacencyGroupRef":"grp-e2e","preferenceVersion":"pv1"}'
B1=$(alloc_body "sb-$(uuid7)" "tvl-$(uuid7)" "hold-$(uuid7)" "$PREF_ADJ"); seat_req POST /api/v1/internal/seat-allocations "$B1"; check_code 201 "allocate first adjacent traveler"; A1=$(jget "['seatAllocationId']"); C1=$(jget "['seatRef']['coachNo']")
B2=$(alloc_body "sb-$(uuid7)" "tvl-$(uuid7)" "hold-$(uuid7)" "$PREF_ADJ"); seat_req POST /api/v1/internal/seat-allocations "$B2"; check_code 201 "allocate second adjacent traveler"; C2=$(jget "['seatRef']['coachNo']"); DEG=$(jget "['seatRef']['degraded']")
[ "$C1" = "$C2" ] || [ "$DEG" = True ] || [ "$DEG" = true ] && ok "adjacency satisfied or degradation fact returned" || bad "adjacency neither satisfied nor degraded"
k exec "$(redis_pod)" -- redis-cli --no-raw XREVRANGE events:seat-assignment + - COUNT 120 >/tmp/seat-events.txt 2>/dev/null || true
grep -q 'AdjacencyGroupCreated' /tmp/seat-events.txt && grep -q 'AdjacentAllocationSolved' /tmp/seat-events.txt && ok "adjacency contract events emitted" || bad "missing adjacency contract events"
PREF_ST='{"acceptStanding":true,"preferenceVersion":"pv-standing"}'
B3=$(alloc_body "sb-$(uuid7)" "tvl-$(uuid7)" "hold-$(uuid7)" "$PREF_ST"); seat_req POST /api/v1/internal/seat-allocations "$B3"; check_code 201 "full SeatMap returns STANDING"; TYPE=$(jget "['seatRef']['allocationType']"); [ "$TYPE" = STANDING ] && ok "STANDING success seatRef" || bad "expected STANDING got $TYPE"
# Real capacity chain for the release test: forged producer events are
# banned (they poison real consumers; ancillary-wave lesson) and lack
# pool-derived segmentRef.
seed_corridor
seed_segment "GST${RANDOM}"
SB_REL="sb-$(uuid7)"
req POST capacity-availability /api/v1/capacity-holds "{\"segmentRef\":\"$SEEDED_SEG\",\"travelerRef\":\"tvl-$(uuid7)\",\"classRef\":\"standard\",\"quantity\":1,\"segmentBookingId\":\"$SB_REL\"}"
check_code 201 "real capacity hold"
REAL_HOLD=$(jget "['holdId']")
# Take the unit and interval capacity-availability actually granted, rather than
# the synthetic defaults -- the release below has to match on all four fields.
#
# From the GET, not the POST: the create response is documented as returning only
# holdId/segmentRef/status/heldUntil, and it does.
req GET capacity-availability "/api/v1/capacity-holds/$REAL_HOLD"
check_code 200 "fetch hold detail"
REAL_UNIT=$(jget "['capacityUnitRef']")
REAL_FROM=$(jget "['interval']['fromSeq']")
REAL_TO=$(jget "['interval']['toSeq']")
if [ -z "$REAL_UNIT" ] || [ -z "$REAL_FROM" ] || [ -z "$REAL_TO" ]; then
  bad "capacity hold response did not expose capacityUnitRef/interval (unit='$REAL_UNIT' interval=$REAL_FROM..$REAL_TO); the release match below cannot work"
fi
B4=$(alloc_body "$SB_REL" "tvl-$(uuid7)" "$REAL_HOLD" "$PREF_ST" "$REAL_UNIT" "$REAL_FROM" "$REAL_TO"); seat_req POST /api/v1/internal/seat-allocations "$B4"; check_code 201 "allocation on real hold"
A4=$(jget "['seatRef']['seatAllocationId']")
req POST capacity-availability "/api/v1/capacity-holds/$REAL_HOLD/release" "{\"releaseReason\":\"REFUND\"}"
check_code 200 "real capacity release"
for i in $(seq 1 12); do seat_req GET "/api/v1/seat-allocations/$A4"; [ "$(jget "['status']")" = RELEASED ] && break; sleep 1; done
[ "$(jget "['status']")" = RELEASED ] && ok "capacity release recovers seat" || bad "release status $(jget "['status']")"
summary
