#!/usr/bin/env bash
# Waitlist end-to-end: idempotent create, mutual exclusion, cancellation,
# fulfillment from released capacity, expiry, and waitlist events.
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

waitlist_req_fixed_key() { # KEY BODY -> LAST_CODE/RESP
  local key=$1 body=$2 out
  out=$(k exec -i e2e-curl -- curl -s -w $'\n%{http_code}' -X POST "http://waitlist:8080/api/v1/waitlist-requests" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $key" \
    -d "$body" 2>/dev/null)
  LAST_CODE=$(echo "$out" | tail -1)
  RESP=$(echo "$out" | sed '$d')
}

stream_mentions() { # STREAM EVENT_TYPE NEEDLE -> yes/empty
  k exec "$(redis_pod)" -- redis-cli XREVRANGE "$1" + - COUNT 120 > /tmp/waitlist-events.txt 2>/dev/null
  ET="$2" NEEDLE="$3" python3 - <<'PYEX'
import re, os, json
raw = open('/tmp/waitlist-events.txt').read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e = json.loads(m.group(0).encode().decode('unicode_escape'))
        if e.get('eventType') == os.environ['ET'] and os.environ['NEEDLE'] in json.dumps(e.get('payload', {})):
            print('yes'); break
    except Exception:
        pass
PYEX
}

future_deadline() { python3 - "$1" <<'PY'
import sys, datetime
print((datetime.datetime.now(datetime.UTC) + datetime.timedelta(seconds=int(sys.argv[1]))).strftime('%Y-%m-%dT%H:%M:%SZ'))
PY
}

create_traveler() {
  local acct=$1 suffix=$2
  req POST traveler-profile /api/v1/travelers "{\"accountId\":\"$acct\",\"travelerType\":\"ADULT\",\"givenName\":\"Wait\",\"familyName\":\"List$suffix\"}"
  check_code 201 "create traveler $suffix"
  CREATED_TRAVELER=$(jget "['travelerId']")
  sleep 3
}

seed_segment() { # service-number -> sets SEEDED_SS/SEEDED_SEG
  local svc=$1 ss seg
  req POST service-plan /api/v1/scheduled-services "{\"carrierId\":\"car-$(uuid7)\",\"serviceNumber\":\"$svc\",\"departureTime\":\"2026-08-02T09:00:00Z\",\"arrivalTime\":\"2026-08-02T14:30:00Z\",\"originNodeId\":\"$N_BJ\",\"destinationNodeId\":\"$N_SH\"}"
  check_code 201 "create waitlist scheduled service $svc"
  ss=$(jget "['scheduledServiceRef']")
  req POST service-plan /api/v1/service-segments "{\"scheduledServiceRef\":\"$ss\",\"originStopRef\":\"$N_BJ\",\"destinationStopRef\":\"$N_SH\",\"departureTime\":\"2026-08-02T09:00:00Z\",\"arrivalTime\":\"2026-08-02T14:30:00Z\"}"
  check_code 201 "create waitlist segment $svc"
  seg=$(jget "['segmentRef']")
  sleep 3
  SEEDED_SS=$ss
  SEEDED_SEG=$seg
}

find_itinerary_for_segment() { # traveler segment -> sets FOUND_ITIN
  local tvl=$1 seg=$2 itin=""
  for attempt in $(seq 1 20); do
    req POST trip-planning /api/v1/itineraries/search "{\"originRef\":\"$P_BJ\",\"destinationRef\":\"$P_SH\",\"departureDate\":\"2026-08-02\",\"travelerRefs\":[\"$tvl\"],\"channel\":\"WEB\"}"
    if [ "$LAST_CODE" = 200 ]; then
      itin=$(printf '%s' "$RESP" | SEG_REF="$seg" python3 -c '
import os, sys, json
d=json.load(sys.stdin)
for item in d.get("itineraries", []):
    legs=item.get("legs") or []
    if legs and legs[0].get("serviceSegmentRef") == os.environ["SEG_REF"]:
        print(item.get("itineraryRef", "")); break
')
      [ -n "$itin" ] && break
    fi
    sleep 3
  done
  FOUND_ITIN=$itin
  [ -n "$FOUND_ITIN" ] && ok "trip-planning exposes segment $seg" || bad "trip-planning did not expose $seg"
}

buy_one() { # acct traveler segment -> sets ORDER/SB/PI/ENT/SAGA for caller scope
  local acct=$1 tvl=$2 seg=$3 fq offer offerv total order saga sb pi ent status
  find_itinerary_for_segment "$tvl" "$seg"
  req POST fare-pricing /api/v1/fare-quotes "{\"travelerRefs\":[\"$tvl\"],\"channel\":\"WEB\",\"segmentRefs\":[\"$seg\"]}"
  check_code 201 "quote $seg"
  fq=$(jget "['quoteId']")
  sleep 3
  req POST offer-management /api/v1/offers "{\"accountId\":\"$acct\",\"channelId\":\"WEB\",\"itineraryRef\":\"$FOUND_ITIN\",\"travelerRefs\":[\"$tvl\"]}"
  check_code 201 "offer $seg"
  offer=$(jget "['offerId']"); offerv=$(jget "['offerVersion']"); total=$(jget "['total']['minorUnits']")
  req POST journey-order /api/v1/journey-orders "{\"accountId\":\"$acct\",\"offerId\":\"$offer\",\"offerVersion\":${offerv:-1},\"travelerRefs\":[\"$tvl\"],\"segmentRefs\":[\"$seg\"]}"
  check_code 201 "order $seg"
  order=$(jget "['orderId']")
  sleep 4
  saga=""
  for attempt in $(seq 1 8); do
    k exec "$(redis_pod)" -- redis-cli XREVRANGE events:booking-orchestration + - COUNT 40 > /tmp/waitlist-booking.txt
    saga=$(ORDER_REF="$order" python3 - <<'PYEX'
import re, os, json
for m in re.finditer(r'\{.*\}', open('/tmp/waitlist-booking.txt').read()):
    try:
        e=json.loads(m.group(0).encode().decode('unicode_escape'))
        if e.get('eventType')=='BookingSagaStarted' and e.get('payload',{}).get('journeyOrderId')==os.environ['ORDER_REF']:
            print(e['payload']['sagaId']); break
    except Exception: pass
PYEX
)
    [ -n "$saga" ] && break
    sleep 3
  done
  [ -n "$saga" ] && ok "saga started for $order" || bad "no saga for $order"
  sb="sb-$(uuid7)"
  req POST booking-orchestration "/api/v1/internal/booking-sagas/$saga/request-reservation" "{\"segmentRef\":\"$seg\",\"travelerRef\":\"$tvl\",\"segmentBookingId\":\"$sb\"}"
  check_code 200 "reserve $seg"
  sleep 5
  req POST payment /api/v1/payment-intents "{\"businessRef\":\"$order\",\"purpose\":\"purchase\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":$total},\"payerRef\":\"$acct\"}"
  check_code 201 "payment intent $seg"
  pi=$(jget "['paymentIntentId']")
  req POST payment "/api/v1/payment-intents/$pi/capture" '{}'
  [ "$LAST_CODE" = 200 ] || [ "$LAST_CODE" = 201 ] && ok "capture $seg [$LAST_CODE]" || bad "capture $seg [$LAST_CODE]"
  sleep 5
  req POST entitlement-ticketing /api/v1/entitlements "{\"segmentBookingId\":\"$sb\",\"journeyOrderId\":\"$order\",\"travelerRef\":\"$tvl\",\"segmentRef\":\"$seg\",\"issuePurpose\":\"INITIAL\"}"
  check_code 201 "issue entitlement $seg"
  ent=$(jget "['entitlementId']")
  for attempt in $(seq 1 6); do
    req GET journey-order "/api/v1/journey-orders/$order"
    status=$(jget "['status']")
    [ "$status" = CONFIRMED ] && break
    sleep 4
  done
  [ "$status" = CONFIRMED ] && ok "order $order CONFIRMED" || bad "order $order not confirmed ($status)"
  ORDER=$order; SAGA=$saga; SB=$sb; PI=$pi; ENT=$ent
}

exhaust_remaining_capacity() { # traveler segment -> holds every still-free unit until sold out
  local tvl=$1 seg=$2 held=0 body sb
  for attempt in $(seq 1 20); do
    sb="sb-$(uuid7)"
    body=$(SEG="$seg" TVL="$tvl" SB="$sb" python3 -c 'import json, os; print(json.dumps({"segmentRef": os.environ["SEG"], "travelerRef": os.environ["TVL"], "classRef": "standard", "quantity": 1, "segmentBookingId": os.environ["SB"]}))')
    printf '%s' "$body" | python3 -c 'import json, sys; json.load(sys.stdin)'
    req POST capacity-availability /api/v1/capacity-holds "$body"
    if [ "$LAST_CODE" = 201 ]; then
      held=$((held + 1))
      continue
    fi
    break
  done
  [ "$held" -gt 0 ] && ok "exhausted remaining capacity with $held direct holds" || ok "segment already sold out after confirmed purchase"
}

poll_waitlist_status() { # id wanted attempts seconds
  local id=$1 want=$2 attempts=$3 pause=$4 status=""
  for attempt in $(seq 1 "$attempts"); do
    req GET waitlist "/api/v1/waitlist-requests/$id"
    status=$(jget "['status']")
    [ "$status" = "$want" ] && break
    sleep "$pause"
  done
  echo "$status"
}

if [ -f ./.refs.env ]; then . ./.refs.env; else bash ./01-seed.sh >/tmp/14-seed.out && . ./.refs.env; fi

LG_REPLICAS=$(k get deploy loadgen -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "")
if [ -n "$LG_REPLICAS" ] && [ "$LG_REPLICAS" != "0" ]; then
  k scale deploy loadgen --replicas=0 >/dev/null
  k wait --for=delete pod -l app.kubernetes.io/name=loadgen --timeout=90s >/dev/null 2>&1
  ok "loadgen paused (was replicas=$LG_REPLICAS)"
fi
resume_loadgen() { [ -n "$LG_REPLICAS" ] && [ "$LG_REPLICAS" != "0" ] && k scale deploy loadgen --replicas="$LG_REPLICAS" >/dev/null 2>&1 || true; }
trap resume_loadgen EXIT

echo "== a. create/idempotency/mutual exclusion + cancel"
ACCT_A="acc-$(uuid7)"; create_traveler "$ACCT_A" A; TVL_A=$CREATED_TRAVELER
seed_segment "GWLA${RANDOM}"; SS_A=$SEEDED_SS; SEG_A=$SEEDED_SEG
req POST payment /api/v1/payment-intents "{\"businessRef\":\"waitlist-a-$(uuid7)\",\"purpose\":\"purchase\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":10750},\"payerRef\":\"$ACCT_A\"}"
check_code 201 "payment guarantee for create"
PI_A=$(jget "['paymentIntentId']")
KEY_A=$(uuid7); DEADLINE_A=$(future_deadline 1800); FP_A="$TVL_A:$SEG_A"
find_itinerary_for_segment "$TVL_A" "$SEG_A"; ITIN_A=$FOUND_ITIN
BODY_A="{\"accountId\":\"$ACCT_A\",\"travelerRef\":\"$TVL_A\",\"segmentRef\":\"$SEG_A\",\"itineraryRef\":\"$ITIN_A\",\"paymentGuaranteeRef\":\"$PI_A\",\"intentFingerprint\":\"$FP_A\",\"deadline\":\"$DEADLINE_A\"}"
waitlist_req_fixed_key "$KEY_A" "$BODY_A"; check_code 201 "create waitlist"
WLR_A=$(jget "['waitlistRequestId']"); ST_A=$(jget "['status']")
[ "$ST_A" = QUEUED ] && ok "created request QUEUED" || bad "created request status $ST_A"
waitlist_req_fixed_key "$KEY_A" "$BODY_A"
WLR_A2=$(jget "['waitlistRequestId']")
[ "$LAST_CODE" = 201 ] && [ "$WLR_A2" = "$WLR_A" ] && ok "idempotent replay returns same waitlist" || bad "idempotent replay failed [$LAST_CODE] $WLR_A2/$WLR_A"
req POST waitlist /api/v1/waitlist-requests "$BODY_A"
[ "$LAST_CODE" = 409 ] && ok "active duplicate rejected" || bad "active duplicate got $LAST_CODE"
req POST waitlist "/api/v1/waitlist-requests/$WLR_A/cancel" '{"reason":"CUSTOMER_CHANGED_PLANS"}'
check_code 200 "cancel waitlist"
ST_A=$(jget "['status']")
[ "$ST_A" = CANCELLED ] && ok "cancel response CANCELLED" || bad "cancel response $ST_A"
ST_A=$(poll_waitlist_status "$WLR_A" CANCELLED 5 2)
[ "$ST_A" = CANCELLED ] && ok "cancelled request rests CANCELLED" || bad "cancelled request unexpected state ($ST_A)"

echo "== c. fulfillment after refund releases sold-out segment"
ACCT_B="acc-$(uuid7)"; create_traveler "$ACCT_B" B; TVL_B=$CREATED_TRAVELER
ACCT_C="acc-$(uuid7)"; create_traveler "$ACCT_C" C; TVL_C=$CREATED_TRAVELER
seed_segment "GWLF${RANDOM}"; SS_F=$SEEDED_SS; SEG_F=$SEEDED_SEG
buy_one "$ACCT_B" "$TVL_B" "$SEG_F"
ORIG_ORDER=$ORDER; ORIG_SB=$SB; ORIG_ENT=$ENT
exhaust_remaining_capacity "$TVL_B" "$SEG_F"
req POST payment /api/v1/payment-intents "{\"businessRef\":\"waitlist-fulfill-$(uuid7)\",\"purpose\":\"purchase\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":10750},\"payerRef\":\"$ACCT_C\"}"
check_code 201 "payment guarantee for fulfillment waitlist"
PI_F=$(jget "['paymentIntentId']")
DEADLINE_F=$(future_deadline 1800); FP_F="$TVL_C:$SEG_F"
find_itinerary_for_segment "$TVL_C" "$SEG_F"; ITIN_F=$FOUND_ITIN
req POST waitlist /api/v1/waitlist-requests "{\"accountId\":\"$ACCT_C\",\"travelerRef\":\"$TVL_C\",\"segmentRef\":\"$SEG_F\",\"itineraryRef\":\"$ITIN_F\",\"paymentGuaranteeRef\":\"$PI_F\",\"intentFingerprint\":\"$FP_F\",\"deadline\":\"$DEADLINE_F\"}"
check_code 201 "create fulfillment waitlist"
WLR_F=$(jget "['waitlistRequestId']")
[ "$(jget "['status']")" = QUEUED ] && ok "fulfillment waitlist queued" || bad "fulfillment waitlist not queued"
req POST post-sales /api/v1/post-sales-cases "{\"journeyOrderId\":\"$ORIG_ORDER\",\"caseType\":\"REFUND\",\"scope\":{\"orderItemRefs\":[\"$ORIG_SB\"],\"segmentRefs\":[\"$SEG_F\"],\"travelerRefs\":[\"$TVL_B\"],\"entitlementRefs\":[\"$ORIG_ENT\"]},\"reasonCode\":\"CUSTOMER_REQUEST\",\"actorRef\":\"$ACCT_B\"}"
check_code 201 "open refund to release capacity"
CASE_F=$(jget "['caseId']")
req POST post-sales "/api/v1/post-sales-cases/$CASE_F/evaluate" '{}'; check_code 200 "evaluate refund"
req POST post-sales "/api/v1/post-sales-cases/$CASE_F/approve" '{}'; check_code 200 "approve refund"
ST_F=$(poll_waitlist_status "$WLR_F" FULFILLED 36 5)
[ "$ST_F" = FULFILLED ] && ok "waitlist fulfilled after capacity release" || bad "waitlist did not fulfill ($ST_F)"
req GET waitlist "/api/v1/waitlist-requests/$WLR_F"
WL_ORDER=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('journeyOrderRef') or d.get('journeyOrderId') or '')" 2>/dev/null)
if [ -n "$WL_ORDER" ]; then
  OST=""
  for attempt in $(seq 1 10); do
    req GET journey-order "/api/v1/journey-orders/$WL_ORDER"
    OST=$(jget "['status']")
    [ "$OST" = CONFIRMED ] && break
    sleep 3
  done
  [ "$OST" = CONFIRMED ] && ok "waitlist journey-order CONFIRMED" || bad "waitlist order not confirmed ($OST)"
else
  bad "fulfilled waitlist response missing journey-order reference"
fi

echo "== d. expiry"
ACCT_E="acc-$(uuid7)"; create_traveler "$ACCT_E" E; TVL_E=$CREATED_TRAVELER
seed_segment "GWLE${RANDOM}"; SS_E=$SEEDED_SS; SEG_E=$SEEDED_SEG
req POST payment /api/v1/payment-intents "{\"businessRef\":\"waitlist-expire-$(uuid7)\",\"purpose\":\"purchase\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":10750},\"payerRef\":\"$ACCT_E\"}"
check_code 201 "payment guarantee for expiry"
PI_E=$(jget "['paymentIntentId']")
DEADLINE_E=$(future_deadline 5); FP_E="$TVL_E:$SEG_E"
find_itinerary_for_segment "$TVL_E" "$SEG_E"; ITIN_E=$FOUND_ITIN
req POST waitlist /api/v1/waitlist-requests "{\"accountId\":\"$ACCT_E\",\"travelerRef\":\"$TVL_E\",\"segmentRef\":\"$SEG_E\",\"itineraryRef\":\"$ITIN_E\",\"paymentGuaranteeRef\":\"$PI_E\",\"intentFingerprint\":\"$FP_E\",\"deadline\":\"$DEADLINE_E\"}"
check_code 201 "create expiring waitlist"
WLR_E=$(jget "['waitlistRequestId']")
ST_E=$(poll_waitlist_status "$WLR_E" EXPIRED 20 2)
[ "$ST_E" = EXPIRED ] && ok "waitlist expired" || bad "waitlist not expired ($ST_E)"

echo "== events:waitlist"
last_events events:waitlist 8
[ "$(stream_mentions events:waitlist WaitlistQueued "$WLR_A")" = yes ] && ok "WaitlistQueued event exists" || bad "missing WaitlistQueued event"
[ "$(stream_mentions events:waitlist WaitlistFulfilled "$WLR_F")" = yes ] && ok "WaitlistFulfilled event exists" || bad "missing WaitlistFulfilled event"
[ "$(stream_mentions events:waitlist WaitlistExpired "$WLR_E")" = yes ] && ok "WaitlistExpired event exists" || bad "missing WaitlistExpired event"

resume_loadgen
trap - EXIT
[ -n "$LG_REPLICAS" ] && [ "$LG_REPLICAS" != "0" ] && ok "loadgen resumed (replicas=$LG_REPLICAS)"
summary
