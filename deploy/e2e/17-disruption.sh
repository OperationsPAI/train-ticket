#!/usr/bin/env bash
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

future_deadline() { python3 - "$1" <<'PY'
import sys, datetime
print((datetime.datetime.now(datetime.UTC) + datetime.timedelta(seconds=int(sys.argv[1]))).strftime('%Y-%m-%dT%H:%M:%SZ'))
PY
}

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

find_itinerary_for_segment() { # traveler segment -> sets FOUND_ITIN
  local tvl=$1 seg=$2 itin=""
  for attempt in $(seq 1 20); do
    req POST trip-planning /api/v1/itineraries/search "{\"originRef\":\"$WL_P_A\",\"destinationRef\":\"$WL_P_B\",\"departureDate\":\"$JOURNEY_DATE\",\"travelerRefs\":[\"$tvl\"],\"channel\":\"WEB\"}"
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


report_body() { local order=$1 suffix=$2 auto=${3:-} acct=${4:-} sb=${5:-} tvl=${6:-} ent=${7:-} seg=${8:-} ss=${9:-}
  [ -n "$seg" ] || seg="seg-0194f2e0-7b3e-7610-8000-${suffix}00000001"
  [ -n "$ss" ] || ss="ssch-dr-$suffix"
  python3 - "$order" "$suffix" "$auto" "$acct" "$sb" "$tvl" "$ent" "$seg" "$ss" "$JOURNEY_DATE" <<'PYJSON'
import json, sys
order,suffix,auto,acct,sb,tvl,ent,seg,ss,service_date=sys.argv[1:]
body={"disruptionType":"SERVICE_DELAY","scheduledServiceRef":ss,"segmentRef":seg,"serviceDate":service_date,"evidence":{"evidenceRef":"ev-"+suffix,"sourceSystem":"ADMIN","sourceRecordId":"row-"+suffix,"summary":"Load-safe disruption drill"},"affectedOrderIds":[order],"reportedBy":{"actorType":"OPERATIONS","actorId":"ops-e2e"}}
if auto: body["autoRecovery"]=auto
if acct: body["accountId"]=acct
if sb or tvl or ent:
    body["refundScope"]={"orderItemRefs":[sb] if sb else [],"segmentRefs":[seg] if seg else [],"travelerRefs":[tvl] if tvl else [],"entitlementRefs":[ent] if ent else []}
print(json.dumps(body,separators=(",",":")))
PYJSON
}

select_option_type() { local case=$1 typ=$2 actor=${3:-USER}
  req GET disruption-recovery "/api/v1/recovery-cases/$case"; check_code 200 "get case for $typ option"
  # `next()` with no default raises StopIteration when the option is absent, which
  # printed a Python traceback into the middle of the run and left OPT empty --
  # the POST below then sent an empty optionId and got a 422, so the real problem
  # (the option was never generated) was reported as a selection failure and the
  # four assertions after it failed as a cascade.
  OPT=$(echo "$RESP" | TYP="$typ" python3 -c '
import json, os, sys
d = json.load(sys.stdin)
opts = d.get("optionSet", {}).get("options") or []
match = next((o["optionId"] for o in opts if o["optionType"] == os.environ["TYP"]), "")
if not match:
    print("AVAILABLE:" + ",".join(sorted(o.get("optionType", "?") for o in opts)), file=sys.stderr)
print(match)
' 2>/tmp/optdiag.$$)
  if [ -z "$OPT" ]; then
    bad "recovery case $case offers no $typ option ($(cat /tmp/optdiag.$$ 2>/dev/null))"
    rm -f /tmp/optdiag.$$
    return 1
  fi
  rm -f /tmp/optdiag.$$
  req POST disruption-recovery "/api/v1/recovery-cases/$case/select-option" "{\"optionId\":\"$OPT\",\"selectedBy\":{\"actorType\":\"$actor\",\"actorId\":\"actor-$(uuid7)\"}}"
}

poll_case_status() { local case=$1 want=$2 attempts=${3:-20} pause=${4:-3} status=""
  for attempt in $(seq 1 "$attempts"); do
    req GET disruption-recovery "/api/v1/recovery-cases/$case"
    status=$(jget "['status']")
    [ "$status" = "$want" ] && break
    sleep "$pause"
  done
  echo "$status"
}

stream_mentions() { local stream=$1 et=$2 needle=$3
  k exec "$(redis_pod)" -- redis-cli XREVRANGE "$stream" + - COUNT 200 >/tmp/dr-events.txt 2>/dev/null
  ET="$et" NEEDLE="$needle" python3 - <<'PYEV'
import os,re,json
raw=open('/tmp/dr-events.txt').read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e=json.loads(m.group(0).encode().decode('unicode_escape'))
        if e.get('eventType')==os.environ['ET'] and os.environ['NEEDLE'] in json.dumps(e.get('payload',{})):
            print('yes'); break
    except Exception: pass
PYEV
}

if [ -f ./.refs.env ]; then . ./.refs.env; else bash ./01-seed.sh >/tmp/17-seed.out && . ./.refs.env; fi
seed_corridor

echo "== disruption report + incident/case reads"
ACCT_A="acc-$(uuid7)"; create_traveler "$ACCT_A" A; TVL_A=$CREATED_TRAVELER
seed_segment "GDR${RANDOM}"; SS_A=$SEEDED_SS; SEG_A=$SEEDED_SEG
buy_one "$ACCT_A" "$TVL_A" "$SEG_A"; ORDER_A=$ORDER; SB_A=$SB; ENT_A=$ENT
SUF_A=$(uuid7 | tr -d '-' | cut -c1-12)
req POST disruption-recovery /api/v1/disruptions "$(report_body "$ORDER_A" "$SUF_A" "" "$ACCT_A" "$SB_A" "$TVL_A" "$ENT_A" "$SEG_A" "$SS_A")"; check_code 202 "report disruption"
INC=$(jget "['incident']['incidentId']"); CASE=$(jget "['recoveryCases'][0]['caseId']")
req GET disruption-recovery "/api/v1/incidents/$INC"; check_code 200 "get incident"
req GET disruption-recovery "/api/v1/recovery-cases/$CASE"; check_code 200 "get case"
req GET disruption-recovery "/api/v1/recovery-cases?incidentId=$INC&limit=20&offset=0"; check_code 200 "list cases by incident"
if [ -n "$(stream_mentions events:disruption-recovery RecoveryCaseOpened "$CASE")" ]; then ok "case-opened event published"; else bad "case-opened event missing"; fi

echo "== WAIT direct recovery chain"
ACCT_W="acc-$(uuid7)"; create_traveler "$ACCT_W" W; TVL_W=$CREATED_TRAVELER
seed_segment "GDW${RANDOM}"; SS_W=$SEEDED_SS; SEG_W=$SEEDED_SEG
buy_one "$ACCT_W" "$TVL_W" "$SEG_W"; ORDER_W=$ORDER; SB_W=$SB; ENT_W=$ENT
SUF_W=$(uuid7 | tr -d '-' | cut -c1-12)
req POST disruption-recovery /api/v1/disruptions "$(report_body "$ORDER_W" "$SUF_W" WAIT "$ACCT_W" "$SB_W" "$TVL_W" "$ENT_W" "$SEG_W" "$SS_W")"; check_code 202 "report wait disruption"
CASE_W=$(jget "['recoveryCases'][0]['caseId']"); ST_W=$(jget "['recoveryCases'][0]['status']")
[ "$ST_W" = RECOVERED ] && ok "WAIT auto recovered" || bad "WAIT status $ST_W"

echo "== REFUND selection executes post-sales and converges"
select_option_type "$CASE" REFUND USER; check_code 200 "select refund"
ST=$(jget "['status']"); [ "$ST" = EXECUTING_RECOVERY ] && ok "refund executing" || bad "refund status $ST"
PSC=$(jget "['execution']['externalRef']")
[ -n "$PSC" ] && ok "post-sales case ref stored" || bad "missing post-sales ref"
req POST post-sales "/api/v1/post-sales-cases/$PSC/evaluate" '{}'; check_code 200 "evaluate disruption refund"
req POST post-sales "/api/v1/post-sales-cases/$PSC/approve" '{}'; check_code 200 "approve disruption refund"
FINAL=$(poll_case_status "$CASE" RECOVERED 12 5)
[ "$FINAL" = RECOVERED ] && ok "refund converged recovered" || bad "refund did not converge recovered ($FINAL)"

echo "== COMPENSATION selection issues wallet benefit"
ACCT_C="acc-$(uuid7)"; create_traveler "$ACCT_C" C; TVL_C=$CREATED_TRAVELER
seed_segment "GDC${RANDOM}"; SS_C=$SEEDED_SS; SEG_C=$SEEDED_SEG
buy_one "$ACCT_C" "$TVL_C" "$SEG_C"; ORDER_C=$ORDER; SB_C=$SB; ENT_C=$ENT
SUF_C=$(uuid7 | tr -d '-' | cut -c1-12)
req POST disruption-recovery /api/v1/disruptions "$(report_body "$ORDER_C" "$SUF_C" "" "$ACCT_C" "$SB_C" "$TVL_C" "$ENT_C" "$SEG_C" "$SS_C")"; check_code 202 "report compensation disruption"
CASE_C=$(jget "['recoveryCases'][0]['caseId']")
select_option_type "$CASE_C" COMPENSATION USER; check_code 200 "select compensation"
[ "$(jget "['status']")" = RECOVERED ] && ok "compensation recovered" || bad "compensation not recovered"
BEN=$(jget "['execution']['externalRef']")
[ -n "$BEN" ] && ok "benefit ref stored" || bad "missing benefit ref"
req GET wallet-promotion "/api/v1/benefits?byAccountId=$ACCT_C&benefitType=COMPENSATION_CREDIT"; check_code 200 "list compensation benefits"
echo "$RESP" | BEN="$BEN" python3 -c 'import json,os,sys; d=json.load(sys.stdin); sys.exit(0 if any(x.get("benefitId")==os.environ["BEN"] for x in d.get("items",[])) else 1)' && ok "wallet benefit appears for account" || bad "wallet benefit missing for account"

echo "== MANUAL resolve then close + illegal transition rejection"
ACCT_M="acc-$(uuid7)"; create_traveler "$ACCT_M" M; TVL_M=$CREATED_TRAVELER
seed_segment "GDM${RANDOM}"; SS_M=$SEEDED_SS; SEG_M=$SEEDED_SEG
buy_one "$ACCT_M" "$TVL_M" "$SEG_M"; ORDER_M=$ORDER; SB_M=$SB; ENT_M=$ENT
SUF_M=$(uuid7 | tr -d '-' | cut -c1-12)
req POST disruption-recovery /api/v1/disruptions "$(report_body "$ORDER_M" "$SUF_M" "" "$ACCT_M" "$SB_M" "$TVL_M" "$ENT_M" "$SEG_M" "$SS_M")"; check_code 202 "report manual disruption"
CASE_M=$(jget "['recoveryCases'][0]['caseId']")
select_option_type "$CASE_M" MANUAL CUSTOMER_SERVICE; check_code 200 "select manual"
[ "$(jget "['status']")" = MANUAL_REVIEW ] && ok "manual review state" || bad "not manual review"
req POST disruption-recovery "/api/v1/recovery-cases/$CASE_M/close" "{\"closedBy\":{\"actorType\":\"OPERATIONS\",\"actorId\":\"ops-e2e\"},\"closeReason\":\"too early\"}"
[ "$LAST_CODE" = 412 ] && ok "manual cannot close directly" || bad "manual close got $LAST_CODE"
req POST disruption-recovery "/api/v1/recovery-cases/$CASE_M/manual-review/resolve" "{\"outcome\":\"RECOVERED\",\"resolvedBy\":{\"actorType\":\"OPERATIONS\",\"actorId\":\"ops-e2e\"},\"reason\":\"manual recovery complete\"}"; check_code 200 "resolve manual"
req POST disruption-recovery "/api/v1/recovery-cases/$CASE_M/close" "{\"closedBy\":{\"actorType\":\"OPERATIONS\",\"actorId\":\"ops-e2e\"},\"closeReason\":\"complete\"}"; check_code 200 "close manual recovered"
[ "$(jget "['status']")" = CLOSED ] && ok "manual closed" || bad "manual close status $(jget "['status']")"

summary
