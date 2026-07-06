#!/usr/bin/env bash
# 正向购票 chain: search → offer → order → booking saga → payment → entitlement.
# Requires 01-seed.sh to have run (reads .refs.env).
cd "$(dirname "$0")" && . ./lib.sh
. ./.refs.env
ensure_curl_pod

ACCT="acc-$(uuid7)"

echo "== 0. register traveler (offer needs a consumed Traveler snapshot)"
req POST traveler-profile /api/v1/travelers "{\"accountId\":\"$ACCT\",\"travelerType\":\"ADULT\",\"givenName\":\"Wei\",\"familyName\":\"Zhang\"}"
check_code 201 "register traveler"
TVL=$(jget "['travelerId']")
echo "  TVL=$TVL"
sleep 3

echo "== 1. trip search"
req POST trip-planning /api/v1/itineraries/search "{\"originRef\":\"$P_BJ\",\"destinationRef\":\"$P_SH\",\"departureDate\":\"2026-08-01\",\"travelerRefs\":[\"$TVL\"],\"channel\":\"WEB\"}"
check_code 200 "search itineraries"
ITIN=$(jget "['itineraries'][0]['itineraryRef']")
SEG_FROM_SEARCH=$(jget "['itineraries'][0]['legs'][0]['serviceSegmentRef']")
echo "  ITIN=$ITIN leg.segment=$SEG_FROM_SEARCH (seeded: $SEG)"
if [ "$SEG_FROM_SEARCH" = "$SEG" ]; then ok "search returns SEEDED segment"; else bad "search returns synthetic segment, not seeded plan data"; fi

echo "== 1b. fare quote (publishes FareQuoted for offer to consume)"
req POST fare-pricing /api/v1/fare-quotes "{\"travelerRefs\":[\"$TVL\"],\"channel\":\"WEB\",\"segmentRefs\":[\"$SEG_FROM_SEARCH\"]}"
check_code 201 "create fare quote"
FQ=$(jget "['quoteId']"); FQ_STATUS=$(jget "['status']")
FQ_TOTAL_MINOR=$(jget "['breakdown']['total']['minorUnits']")
echo "  FQ=$FQ status=$FQ_STATUS totalMinor=$FQ_TOTAL_MINOR"
sleep 3

echo "== 2. create offer"
req POST offer-management /api/v1/offers "{\"accountId\":\"$ACCT\",\"channelId\":\"WEB\",\"itineraryRef\":\"$ITIN\",\"travelerRefs\":[\"$TVL\"]}"
check_code 201 "create offer"
OFFER=$(jget "['offerId']"); OFFERV=$(jget "['offerVersion']")
TOTAL=$(jget "['total']")
OFFER_TOTAL_MINOR=$(jget "['total']['minorUnits']")
echo "  OFFER=$OFFER v$OFFERV total=$TOTAL"
[ "$OFFER_TOTAL_MINOR" = "$FQ_TOTAL_MINOR" ] && ok "offer total follows fare quote" || bad "offer total $OFFER_TOTAL_MINOR does not match fare quote $FQ_TOTAL_MINOR"

echo "== 3. create journey order"
req POST journey-order /api/v1/journey-orders "{\"accountId\":\"$ACCT\",\"offerId\":\"$OFFER\",\"offerVersion\":${OFFERV:-1},\"travelerRefs\":[\"$TVL\"],\"segmentRefs\":[\"$SEG_FROM_SEARCH\"]}"
check_code 201 "create journey order"
ORDER=$(jget "['orderId']"); STATUS=$(jget "['status']")
echo "  ORDER=$ORDER status=$STATUS"

echo "== 4. saga started (JourneyOrderCreated -> booking-orchestration)"
sleep 4
last_events events:journey-order 1
last_events events:booking-orchestration 1
SAGA=""
for attempt in 1 2 3 4 5 6; do
  k exec $(redis_pod) -- redis-cli XREVRANGE events:booking-orchestration + - COUNT 10 > /tmp/bstream.txt
  SAGA=$(ORDER="$ORDER" python3 - << 'PYEX'
import json, re, os
raw = open("/tmp/bstream.txt").read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e = json.loads(m.group(0).encode().decode('unicode_escape'))
        if e.get("eventType") == "BookingSagaStarted" and e["payload"].get("journeyOrderId") == os.environ["ORDER"]:
            print(e["payload"]["sagaId"]); break
    except Exception: pass
PYEX
)
  [ -n "$SAGA" ] && break
  sleep 4
done
if [ -n "$SAGA" ]; then ok "BookingSagaStarted for our order (saga=$SAGA)"; else bad "no saga found for order"; fi

echo "== 4b. drive reservation step"
SB="sb-$(uuid7)"
req POST booking-orchestration "/api/v1/internal/booking-sagas/$SAGA/request-reservation" "{\"segmentRef\":\"$SEG_FROM_SEARCH\",\"travelerRef\":\"$TVL\",\"segmentBookingId\":\"$SB\"}"
check_code 200 "request segment reservation"
echo "  $(echo $RESP | head -c 160)"
sleep 5
echo "  -- capacity reaction:"
last_events events:capacity-availability 2
echo "  -- booking stream now:"
last_events events:booking-orchestration 3

echo "== 5. payment (contract fields) + capture"
PAYMENT_MINOR=${OFFER_TOTAL_MINOR:-$FQ_TOTAL_MINOR}
req POST payment /api/v1/payment-intents "{\"businessRef\":\"$ORDER\",\"purpose\":\"purchase\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":$PAYMENT_MINOR},\"payerRef\":\"$ACCT\"}"
check_code 201 "create payment intent"
PI=$(jget "['paymentIntentId']")
echo "  PI=$PI"
req POST payment "/api/v1/payment-intents/$PI/capture" '{}'
[ "$LAST_CODE" = 200 ] || [ "$LAST_CODE" = 201 ] && ok "capture payment [$LAST_CODE]" || bad "capture payment [$LAST_CODE]"
sleep 5

echo "== 6. downstream after payment"
echo "  events:payment:"; last_events events:payment 2
echo "  booking stream:"; last_events events:booking-orchestration 2
echo "  entitlement stream:"; last_events events:entitlement-ticketing 2
req GET journey-order "/api/v1/journey-orders/$ORDER"
echo "  order status: $(jget "['status']") [$LAST_CODE]"


echo "== 7. issue entitlement (ticketing leg)"
req POST entitlement-ticketing /api/v1/entitlements "{\"segmentBookingId\":\"$SB\",\"journeyOrderId\":\"$ORDER\",\"travelerRef\":\"$TVL\",\"segmentRef\":\"$SEG_FROM_SEARCH\",\"issuePurpose\":\"INITIAL\"}"
check_code 201 "issue entitlement"
ENT=$(jget "['entitlementId']")
echo "  ENT=$ENT"
sleep 6

echo "== 8. saga completion + final states"
echo "  entitlement stream:"; last_events events:entitlement-ticketing 2
echo "  booking stream:"; last_events events:booking-orchestration 3
SAGA_STATUS=""
for attempt in 1 2 3; do
  req GET booking-orchestration "/api/v1/internal/booking-sagas/$SAGA"
  SAGA_STATUS=$(jget "['status']")
  [ "$SAGA_STATUS" = "COMPLETED" ] && break
  sleep 4
done
echo "  saga: $(echo $RESP | head -c 220) [$LAST_CODE]"
[ "$SAGA_STATUS" = "COMPLETED" ] && ok "saga COMPLETED" || bad "saga not completed ($SAGA_STATUS)"
STEP_STATES=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(','.join(sorted(set(s['status'] for s in d.get('steps',[]))) or ['NONE']))" 2>/dev/null)
[ "$STEP_STATES" = "SUCCEEDED" ] && ok "all saga steps SUCCEEDED" || bad "saga steps not all SUCCEEDED ($STEP_STATES)"
ORDER_STATUS=""
for attempt in 1 2 3; do
  req GET journey-order "/api/v1/journey-orders/$ORDER"
  ORDER_STATUS=$(jget "['status']")
  [ "$ORDER_STATUS" = "CONFIRMED" ] && break
  sleep 4
done
echo "  order status: $ORDER_STATUS [$LAST_CODE]"
[ "$ORDER_STATUS" = "CONFIRMED" ] && ok "order CONFIRMED" || bad "order not confirmed ($ORDER_STATUS)"

cat >> ./.refs.env << EOF
ACCT=$ACCT
TVL=$TVL
ITIN=$ITIN
OFFER=$OFFER
ORDER=$ORDER
PI=$PI
SB=$SB
SAGA=$SAGA
ENT=$ENT
FQ_TOTAL_MINOR=$FQ_TOTAL_MINOR
OFFER_TOTAL_MINOR=$OFFER_TOTAL_MINOR
EOF
summary
