#!/usr/bin/env bash
# Seed reference data: places, transport nodes, scheduled service + segment.
# Downstream state (trip-planning candidates, capacity snapshots) is built by
# consuming service-plan events — this script then verifies that propagation.
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

echo "== 1. places"
req POST place-network /api/v1/places '{"canonicalName":"Beijing","placeType":"CITY","code":"BJS","timezone":"Asia/Shanghai"}'
P_BJ=$(jget "['placeId']")
check_code 201 "create place Beijing"
req POST place-network /api/v1/places '{"canonicalName":"Shanghai","placeType":"CITY","code":"SHA","timezone":"Asia/Shanghai"}'
P_SH=$(jget "['placeId']")
check_code 201 "create place Shanghai"
echo "  P_BJ=$P_BJ P_SH=$P_SH"

echo "== 2. transport nodes"
req POST place-network /api/v1/transport-nodes "{\"placeId\":\"$P_BJ\",\"displayName\":\"Beijing South\",\"servingModes\":[\"RAIL\"]}"
N_BJ=$(jget "['nodeId']")
check_code 201 "create node Beijing South"
req POST place-network /api/v1/transport-nodes "{\"placeId\":\"$P_SH\",\"displayName\":\"Shanghai Hongqiao\",\"servingModes\":[\"RAIL\"]}"
N_SH=$(jget "['nodeId']")
check_code 201 "create node Shanghai Hongqiao"
echo "  N_BJ=$N_BJ N_SH=$N_SH"

# The seeded service must be a FUTURE departure, and every later script in
# the chain has to agree on the date -- so it is derived once here from
# lib.sh's JOURNEY_DATE and persisted through .refs.env rather than being a
# literal that silently ages into the past.
SERVICE_DATE="$JOURNEY_DATE"
echo "== 3. scheduled service G1234 ($SERVICE_DATE)"
req POST service-plan /api/v1/scheduled-services "{\"carrierId\":\"car-$(uuid7)\",\"serviceNumber\":\"G1234\",\"departureTime\":\"${SERVICE_DATE}T09:00:00Z\",\"arrivalTime\":\"${SERVICE_DATE}T14:30:00Z\",\"originNodeId\":\"$N_BJ\",\"destinationNodeId\":\"$N_SH\"}"
SS=$(jget "['scheduledServiceRef']")
check_code 201 "create scheduled service"
echo "  SS=$SS"

echo "== 4. service segment"
req POST service-plan /api/v1/service-segments "{\"scheduledServiceRef\":\"$SS\",\"originStopRef\":\"$N_BJ\",\"destinationStopRef\":\"$N_SH\",\"departureTime\":\"${SERVICE_DATE}T09:00:00Z\",\"arrivalTime\":\"${SERVICE_DATE}T14:30:00Z\"}"
SEG=$(jget "['segmentRef']")
check_code 201 "create service segment"
echo "  SEG=$SEG"

echo "== 5. event propagation"
sleep 3
echo "  events:service-plan XLEN=$(xlen events:service-plan)"
last_events events:service-plan 3
echo "  events:place-network XLEN=$(xlen events:place-network)"

echo "== 6. downstream state built from events?"
req GET capacity-availability "/api/v1/availability-snapshots?scheduledServiceRef=$SS&segmentRef=$SEG"
SNAP="$RESP"
echo "  capacity snapshot [$LAST_CODE]: $(echo "$SNAP" | head -c 220)"

# persist refs for the next script
cat > /tmp/e2e-refs.env << EOF
P_BJ=$P_BJ
P_SH=$P_SH
N_BJ=$N_BJ
N_SH=$N_SH
SS=$SS
SEG=$SEG
SERVICE_DATE=$SERVICE_DATE
EOF
# script already cd'd to its own dir at the top; a second dirname "$0"
# here resolves wrong when invoked from the repo root and the copy was
# silently skipped, breaking every later script's `. ./.refs.env`.
cp /tmp/e2e-refs.env ./.refs.env
summary
