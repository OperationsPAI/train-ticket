#!/usr/bin/env bash
# Managed fare rules: publish API rule set → quote/purchase at new amount → refund uses new fee.
cd "$(dirname "$0")" && . ./lib.sh
. ./.refs.env
ensure_curl_pod

stream_has_event() {
  local event_type=$1 rule_set_id=$2
  k exec "$(redis_pod)" -- redis-cli XREVRANGE events:fare-pricing + - COUNT 80 > /tmp/fare-rule-events.txt 2>/dev/null
  EVENT_TYPE="$event_type" RULE_SET_ID="$rule_set_id" python3 - << 'PYEX'
import json, os, re
raw = open('/tmp/fare-rule-events.txt').read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e = json.loads(m.group(0).encode().decode('unicode_escape'))
    except Exception:
        continue
    if e.get('eventType') == os.environ['EVENT_TYPE'] and e.get('payload', {}).get('ruleSetId') == os.environ['RULE_SET_ID']:
        print('yes')
        break
PYEX
}

retry_event() {
  local event_type=$1 rule_set_id=$2 ok_seen=""
  for attempt in 1 2 3 4 5 6; do
    ok_seen=$(stream_has_event "$event_type" "$rule_set_id")
    [ "$ok_seen" = "yes" ] && break
    sleep 3
  done
  [ "$ok_seen" = "yes" ] && ok "$event_type published" || bad "$event_type not observed for $rule_set_id"
}

ACCT_RULE="acc-$(uuid7)"
RULE_VERSION="e2e-$(date -u +%Y%m%d%H%M%S)"
RULE_BODY=$(cat <<JSON
{"supplierId":"supplier-e2e","contractId":"contract-e2e","productCode":"rail-standard","mode":"rail","channel":"WEB","version":"$RULE_VERSION","effectiveWindow":{"startsAt":"2026-07-01T00:00:00Z","endsAt":"2026-12-31T00:00:00Z"},"rules":[{"ruleId":"base-e2e","kind":"base_fare","amount":{"currency":"CNY","minorUnits":12000},"explanation":{"code":"fare.base.e2e","parameters":{"source":"07-fare-rules"}},"refundable":true},{"ruleId":"refund-e2e","kind":"refund_fee","amount":{"currency":"CNY","minorUnits":3000},"explanation":{"code":"fare.refund.e2e","parameters":{"source":"07-fare-rules"}},"refundable":true},{"ruleId":"change-e2e","kind":"change_fee","amount":{"currency":"CNY","minorUnits":1500},"explanation":{"code":"fare.change.e2e","parameters":{"source":"07-fare-rules"}},"refundable":true}]}
JSON
)

echo "== 1. create and publish managed fare rule set"
req POST fare-pricing /api/v1/fare-rule-sets "$RULE_BODY"
check_code 201 "create fare rule set"
RULE_SET=$(jget "['ruleSetId']")
echo "  RULE_SET=$RULE_SET"
req POST fare-pricing "/api/v1/fare-rule-sets/$RULE_SET/publish" '{}'
check_code 200 "publish fare rule set"
retry_event FareRuleSetPublished "$RULE_SET"

echo "== 2. register traveler and quote new fare"
req POST traveler-profile /api/v1/travelers "{\"accountId\":\"$ACCT_RULE\",\"travelerType\":\"ADULT\",\"givenName\":\"Rule\",\"familyName\":\"Tester\"}"
check_code 201 "register traveler"
TVL_RULE=$(jget "['travelerId']")
sleep 3
req POST trip-planning /api/v1/itineraries/search "{\"originRef\":\"$P_BJ\",\"destinationRef\":\"$P_SH\",\"departureDate\":\"2026-08-01\",\"travelerRefs\":[\"$TVL_RULE\"],\"channel\":\"WEB\"}"
check_code 200 "search itineraries"
ITIN_RULE=$(jget "['itineraries'][0]['itineraryRef']")
SEG_RULE=$(jget "['itineraries'][0]['legs'][0]['serviceSegmentRef']")
req POST fare-pricing /api/v1/fare-quotes "{\"travelerRefs\":[\"$TVL_RULE\"],\"channel\":\"WEB\",\"segmentRefs\":[\"$SEG_RULE\"]}"
check_code 201 "create fare quote with managed rules"
QUOTE_TOTAL=$(jget "['breakdown']['total']['minorUnits']")
QUOTE_RULE_SET=$(jget "['ruleSnapshot']['ruleSetId']")
[ "$QUOTE_TOTAL" = "12000" ] && ok "fare quote total = 120.00 CNY" || bad "fare quote total wrong ($QUOTE_TOTAL)"
[ "$QUOTE_RULE_SET" = "$RULE_SET" ] && ok "fare quote uses managed rule set" || bad "fare quote used $QUOTE_RULE_SET"
sleep 3

echo "== 3. offer/order/payment use new fare amount"
req POST offer-management /api/v1/offers "{\"accountId\":\"$ACCT_RULE\",\"channelId\":\"WEB\",\"itineraryRef\":\"$ITIN_RULE\",\"travelerRefs\":[\"$TVL_RULE\"]}"
check_code 201 "create offer"
OFFER_RULE=$(jget "['offerId']"); OFFERV_RULE=$(jget "['offerVersion']"); OFFER_RULE_TOTAL=$(jget "['total']['minorUnits']")
[ "$OFFER_RULE_TOTAL" = "12000" ] && ok "offer total = 120.00 CNY" || bad "offer total wrong ($OFFER_RULE_TOTAL)"
req POST journey-order /api/v1/journey-orders "{\"accountId\":\"$ACCT_RULE\",\"offerId\":\"$OFFER_RULE\",\"offerVersion\":${OFFERV_RULE:-1},\"travelerRefs\":[\"$TVL_RULE\"],\"segmentRefs\":[\"$SEG_RULE\"]}"
check_code 201 "create journey order"
ORDER_RULE=$(jget "['orderId']")
sleep 4
SAGA_RULE=""
for attempt in 1 2 3 4 5 6; do
  k exec "$(redis_pod)" -- redis-cli XREVRANGE events:booking-orchestration + - COUNT 20 > /tmp/rule-booking.txt
  SAGA_RULE=$(ORDER="$ORDER_RULE" python3 - << 'PYEX'
import json, os, re
raw = open('/tmp/rule-booking.txt').read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e = json.loads(m.group(0).encode().decode('unicode_escape'))
    except Exception:
        continue
    if e.get('eventType') == 'BookingSagaStarted' and e.get('payload', {}).get('journeyOrderId') == os.environ['ORDER']:
        print(e['payload']['sagaId'])
        break
PYEX
)
  [ -n "$SAGA_RULE" ] && break
  sleep 4
done
[ -n "$SAGA_RULE" ] && ok "BookingSagaStarted for managed-rule order" || bad "no saga for managed-rule order"
SB_RULE="sb-$(uuid7)"
req POST booking-orchestration "/api/v1/internal/booking-sagas/$SAGA_RULE/request-reservation" "{\"segmentRef\":\"$SEG_RULE\",\"travelerRef\":\"$TVL_RULE\",\"segmentBookingId\":\"$SB_RULE\"}"
check_code 200 "request segment reservation"
sleep 5
req POST payment /api/v1/payment-intents "{\"businessRef\":\"$ORDER_RULE\",\"purpose\":\"purchase\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":$OFFER_RULE_TOTAL},\"payerRef\":\"$ACCT_RULE\"}"
check_code 201 "create payment intent at offer total"
PI_RULE=$(jget "['paymentIntentId']")
PI_AMOUNT=$(jget "['amount']['minorUnits']")
if [ -z "$PI_AMOUNT" ]; then PI_AMOUNT=$OFFER_RULE_TOTAL; fi
[ "$PI_AMOUNT" = "12000" ] && ok "payment intent amount = offer total" || bad "payment intent amount wrong ($PI_AMOUNT)"
req POST payment "/api/v1/payment-intents/$PI_RULE/capture" '{}'
[ "$LAST_CODE" = 200 ] || [ "$LAST_CODE" = 201 ] && ok "capture payment [$LAST_CODE]" || bad "capture payment [$LAST_CODE]"
sleep 5
req POST entitlement-ticketing /api/v1/entitlements "{\"segmentBookingId\":\"$SB_RULE\",\"journeyOrderId\":\"$ORDER_RULE\",\"travelerRef\":\"$TVL_RULE\",\"segmentRef\":\"$SEG_RULE\",\"issuePurpose\":\"INITIAL\"}"
check_code 201 "issue entitlement"
ENT_RULE=$(jget "['entitlementId']")
sleep 6

echo "== 4. refund uses managed refund fee"
req POST post-sales /api/v1/post-sales-cases "{\"journeyOrderId\":\"$ORDER_RULE\",\"caseType\":\"REFUND\",\"scope\":{\"orderItemRefs\":[\"$SB_RULE\"],\"segmentRefs\":[\"$SEG_RULE\"],\"travelerRefs\":[\"$TVL_RULE\"],\"entitlementRefs\":[\"$ENT_RULE\"]},\"reasonCode\":\"CUSTOMER_REQUEST\",\"actorRef\":\"$ACCT_RULE\"}"
check_code 201 "open post-sales case"
CASE_RULE=$(jget "['caseId']")
req POST post-sales "/api/v1/post-sales-cases/$CASE_RULE/evaluate" '{}'
check_code 200 "evaluate refund"
REFUND_RULE=$(jget "['refundableAmount']['minorUnits']")
[ "$REFUND_RULE" = "9000" ] && ok "refundable = 90.00 CNY (120.00 - 30.00)" || bad "refundable wrong ($REFUND_RULE, expected 9000)"

summary
