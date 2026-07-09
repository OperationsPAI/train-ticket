#!/usr/bin/env bash
# Payment Channel e2e: real purchase chain drives the SIM channel handoff,
# original-route refund, daily statements, and finance reconciliation intake.
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

create_traveler() {
  local acct=$1 suffix=$2
  req POST traveler-profile /api/v1/travelers "{\"accountId\":\"$acct\",\"travelerType\":\"ADULT\",\"givenName\":\"Wait\",\"familyName\":\"List$suffix\"}"
  check_code 201 "create traveler $suffix"
  CREATED_TRAVELER=$(jget "['travelerId']")
  sleep 3
  verify_traveler "$CREATED_TRAVELER"
}

seed_segment() { # service-number -> sets SEEDED_SS/SEEDED_SEG
  local svc=$1 ss seg
  req POST service-plan /api/v1/scheduled-services "{\"carrierId\":\"car-$(uuid7)\",\"serviceNumber\":\"$svc\",\"departureTime\":\"2026-08-02T09:00:00Z\",\"arrivalTime\":\"2026-08-02T14:30:00Z\",\"originNodeId\":\"$WL_N_A\",\"destinationNodeId\":\"$WL_N_B\"}"
  check_code 201 "create waitlist scheduled service $svc"
  ss=$(jget "['scheduledServiceRef']")
  req POST service-plan /api/v1/service-segments "{\"scheduledServiceRef\":\"$ss\",\"originStopRef\":\"$WL_N_A\",\"destinationStopRef\":\"$WL_N_B\",\"departureTime\":\"2026-08-02T09:00:00Z\",\"arrivalTime\":\"2026-08-02T14:30:00Z\"}"
  check_code 201 "create waitlist segment $svc"
  seg=$(jget "['segmentRef']")
  sleep 3
  SEEDED_SS=$ss
  SEEDED_SEG=$seg
}

find_itinerary_for_segment() { # traveler segment -> sets FOUND_ITIN
  local tvl=$1 seg=$2 itin=""
  for attempt in $(seq 1 20); do
    req POST trip-planning /api/v1/itineraries/search "{\"originRef\":\"$WL_P_A\",\"destinationRef\":\"$WL_P_B\",\"departureDate\":\"2026-08-02\",\"travelerRefs\":[\"$tvl\"],\"channel\":\"WEB\"}"
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


RUN=$(uuid7 | tail -c 13)
TODAY=$(date -u +%F)

# --- real purchase: quote -> offer -> order -> saga -> captured payment ---
seed_corridor
ACCT="acc-$(uuid7)"; create_traveler "$ACCT" PC; TVL=$CREATED_TRAVELER
seed_segment "GPC${RANDOM}"
buy_one "$ACCT" "$TVL" "$SEEDED_SEG"
[ -n "$PI" ] && ok "real payment intent $PI" || bad "no payment intent from buy chain"

CHREF=""
for i in $(seq 1 30); do
  req GET payment "/api/v1/payment-intents/$PI"
  PSTATUS=$(jget "['status']")
  CHREF=$(jget "['channelRef']['channelOrderId']")
  [ "$PSTATUS" = CAPTURED ] && [ -n "$CHREF" ] && break
  sleep 2
done
[ "$PSTATUS" = CAPTURED ] && ok "payment captured via channel" || bad "payment status $PSTATUS"
[ -n "$CHREF" ] && ok "channelRef backfilled ($CHREF)" || bad "channelRef missing"

req GET payment-channel "/api/v1/channel-orders/$CHREF"
check_code 200 "channel order readable"
[ "$(jget "['status']")" = SUCCEEDED ] && ok "channel order succeeded" || bad "channel order status $(jget "['status']")"
[ "$(jget "['businessRef']")" = "$PI" ] && ok "channel order bound to intent" || bad "channel businessRef $(jget "['businessRef']")"

# --- original-route refund via post-sales ---
req POST post-sales /api/v1/post-sales-cases "{\"journeyOrderId\":\"$ORDER\",\"caseType\":\"REFUND\",\"openedBy\":{\"actorType\":\"CUSTOMER\",\"actorRef\":\"$TVL\"},\"reasonCode\":\"CUSTOMER_REQUEST\",\"scope\":{\"orderItemRefs\":[\"$SB\"],\"segmentRefs\":[\"$SEEDED_SEG\"],\"travelerRefs\":[\"$TVL\"],\"entitlementRefs\":[\"$ENT\"]}}"
check_code 201 "refund case opened"
CASE=$(jget "['caseId']")
req POST post-sales "/api/v1/post-sales-cases/$CASE/evaluate" '{}'
check_code 200 "refund evaluated"
req POST post-sales "/api/v1/post-sales-cases/$CASE/approve" "{\"approvedBy\":{\"actorType\":\"OPERATIONS\",\"actorId\":\"ops-e2e\"}}"
check_code 200 "refund approved"

CHREFUND=""
for i in $(seq 1 30); do
  k exec "$(redis_pod)" -- redis-cli XREVRANGE events:payment + - COUNT 60 > /tmp/paychan-events.txt 2>/dev/null
  CHREFUND=$(PI_REF="$PI" python3 - <<'PYX'
import re, os, json
for m in re.finditer(r'\{.*\}', open('/tmp/paychan-events.txt').read()):
    try:
        e = json.loads(m.group(0).encode().decode('unicode_escape'))
        if e.get('eventType') == 'RefundSettled' and e.get('payload', {}).get('paymentIntentId') == os.environ['PI_REF']:
            print(e['payload'].get('channelRef', {}).get('channelRefundId', ''))
            break
    except Exception:
        pass
PYX
)
  [ -n "$CHREFUND" ] && break
  sleep 2
done
[ -n "$CHREFUND" ] && ok "original-route channel refund ($CHREFUND)" || bad "channel refund missing"
req GET payment-channel "/api/v1/channel-refunds/$CHREFUND"
check_code 200 "channel refund readable"
[ "$(jget "['status']")" = SUCCEEDED ] && ok "channel refund succeeded" || bad "channel refund status $(jget "['status']")"

# --- daily statement + freeze + finance intake ---
req POST payment-channel /api/v1/channel-statements/generate "{\"channel\":\"ALIPAY_SIM\",\"statementDate\":\"$TODAY\",\"currency\":\"CNY\",\"seedVersion\":\"v1\",\"operatorRef\":\"e2e-$RUN\",\"reasonCode\":\"E2E\"}"
check_code 201 "statement generated"
SID=$(jget "['channelStatementId']"); SH=$(jget "['statementHash']"); SVER=$(jget "['version']")
req POST payment-channel "/api/v1/channel-statements/$SID/freeze" "{\"statementHash\":\"$SH\",\"expectedVersion\":$SVER,\"operatorRef\":\"e2e-$RUN\",\"reasonCode\":\"E2E\"}"
check_code 200 "statement frozen"

FOUND=""
for i in $(seq 1 20); do
  req GET finance-settlement "/api/v1/channel-statements?channel=ALIPAY_SIM&statementDate=$TODAY"
  FOUND=$(echo "$RESP" | python3 -c "import sys,json
d=json.load(sys.stdin)
items=d.get('items', d if isinstance(d, list) else [])
print(next((i['channelStatementId'] for i in items if i.get('channelStatementId')=='$SID'), ''))" 2>/dev/null)
  [ -n "$FOUND" ] && break
  sleep 2
done
[ -n "$FOUND" ] && ok "finance ingested frozen statement" || bad "finance statement read model missing $SID"

# --- discrepancy lifecycle on the real statement ---
req POST payment-channel /api/v1/channel-discrepancies "{\"channelStatementId\":\"$SID\",\"differenceType\":\"AMOUNT_MISMATCH\",\"expectedAmount\":{\"currency\":\"CNY\",\"minorUnits\":100},\"actualAmount\":{\"currency\":\"CNY\",\"minorUnits\":101},\"evidenceRef\":\"ev-$RUN\"}"
check_code 201 "discrepancy opened"
DID=$(jget "['discrepancyId']"); DVER=$(jget "['version']")
req POST payment-channel "/api/v1/channel-discrepancies/$DID/resolve" "{\"resolutionStatus\":\"RESOLVED\",\"resolutionRef\":\"res-$RUN\",\"operatorRef\":\"e2e-$RUN\",\"reasonCode\":\"MATCHED\",\"expectedVersion\":$DVER}"
check_code 200 "discrepancy resolved"

summary
