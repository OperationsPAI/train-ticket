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

quote_total_with_retry() {
  local product_code=$1 traveler_ref=$2 segment_ref=$3 total_var=$4 rule_set_var=$5
  local body total rule_set
  if [ -n "$product_code" ]; then
    body="{\"travelerRefs\":[\"$traveler_ref\"],\"channel\":\"WEB\",\"segmentRefs\":[\"$segment_ref\"],\"productCode\":\"$product_code\"}"
  else
    body="{\"travelerRefs\":[\"$traveler_ref\"],\"channel\":\"WEB\",\"segmentRefs\":[\"$segment_ref\"]}"
  fi
  total=""; rule_set=""
  for attempt in 1 2 3 4 5 6; do
    req POST fare-pricing /api/v1/fare-quotes "$body"
    if [ "$LAST_CODE" = "201" ]; then
      total=$(jget "['breakdown']['total']['minorUnits']")
      rule_set=$(jget "['ruleSnapshot']['ruleSetId']")
      [ -n "$total" ] && break
    fi
    sleep 2
  done
  eval "$total_var=\$total"
  eval "$rule_set_var=\$rule_set"
}

ACCT_RULE="acc-$(uuid7)"
RULE_VERSION="e2e-$(date -u +%Y%m%d%H%M%S)"
RULE_BODY=$(cat <<JSON
{"supplierId":"supplier-e2e","contractId":"contract-e2e","productCode":"rail-standard","mode":"rail","channel":"WEB","version":"$RULE_VERSION","effectiveWindow":{"startsAt":"$WINDOW_STARTS_AT","endsAt":"$WINDOW_ENDS_AT"},"rules":[{"ruleId":"base-e2e","kind":"base_fare","amount":{"currency":"CNY","minorUnits":12000},"explanation":{"code":"fare.base.e2e","parameters":{"source":"07-fare-rules"}},"refundable":true},{"ruleId":"refund-e2e","kind":"refund_fee","amount":{"currency":"CNY","minorUnits":3000},"explanation":{"code":"fare.refund.e2e","parameters":{"source":"07-fare-rules"}},"refundable":true},{"ruleId":"change-e2e","kind":"change_fee","amount":{"currency":"CNY","minorUnits":1500},"explanation":{"code":"fare.change.e2e","parameters":{"source":"07-fare-rules"}},"refundable":true}]}
JSON
)
BUSINESS_VERSION="$RULE_VERSION-business"
BUSINESS_BODY=$(cat <<JSON
{"supplierId":"supplier-e2e","contractId":"contract-e2e-business","productCode":"rail-business","mode":"rail","channel":"WEB","version":"$BUSINESS_VERSION","effectiveWindow":{"startsAt":"$WINDOW_STARTS_AT","endsAt":"$WINDOW_ENDS_AT"},"rules":[{"ruleId":"base-business-e2e","kind":"base_fare","amount":{"currency":"CNY","minorUnits":18000},"explanation":{"code":"fare.base.business.e2e","parameters":{"source":"07-fare-rules"}},"refundable":true},{"ruleId":"refund-business-e2e","kind":"refund_fee","amount":{"currency":"CNY","minorUnits":4000},"explanation":{"code":"fare.refund.business.e2e","parameters":{"source":"07-fare-rules"}},"refundable":true},{"ruleId":"change-business-e2e","kind":"change_fee","amount":{"currency":"CNY","minorUnits":2000},"explanation":{"code":"fare.change.business.e2e","parameters":{"source":"07-fare-rules"}},"refundable":true}]}
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
req POST fare-pricing /api/v1/fare-rule-sets "$BUSINESS_BODY"
check_code 201 "create business fare rule set"
BUSINESS_RULE_SET=$(jget "['ruleSetId']")
echo "  BUSINESS_RULE_SET=$BUSINESS_RULE_SET"
req POST fare-pricing "/api/v1/fare-rule-sets/$BUSINESS_RULE_SET/publish" '{}'
check_code 200 "publish business fare rule set"
retry_event FareRuleSetPublished "$BUSINESS_RULE_SET"

echo "== 2. register traveler and quote new fare"
req POST traveler-profile /api/v1/travelers "{\"accountId\":\"$ACCT_RULE\",\"travelerType\":\"ADULT\",\"givenName\":\"Rule\",\"familyName\":\"Tester\"}"
check_code 201 "register traveler"
TVL_RULE=$(jget "['travelerId']")
verify_traveler "${TVL_RULE}"
sleep 3
req POST trip-planning /api/v1/itineraries/search "{\"originRef\":\"$P_BJ\",\"destinationRef\":\"$P_SH\",\"departureDate\":\"${SERVICE_DATE:-$JOURNEY_DATE}\",\"travelerRefs\":[\"$TVL_RULE\"],\"channel\":\"WEB\"}"
check_code 200 "search itineraries"
ITIN_RULE=$(jget "['itineraries'][0]['itineraryRef']")
SEG_RULE=$(jget "['itineraries'][0]['legs'][0]['serviceSegmentRef']")
quote_total_with_retry "" "$TVL_RULE" "$SEG_RULE" QUOTE_TOTAL QUOTE_RULE_SET
[ "$QUOTE_TOTAL" = "12000" ] && ok "fare quote total = 120.00 CNY" || bad "fare quote total wrong ($QUOTE_TOTAL)"
[ "$QUOTE_RULE_SET" = "$RULE_SET" ] && ok "fare quote uses managed rule set" || bad "fare quote used $QUOTE_RULE_SET"
quote_total_with_retry "rail-business" "$TVL_RULE" "$SEG_RULE" BUSINESS_QUOTE_TOTAL BUSINESS_QUOTE_RULE_SET
[ "$BUSINESS_QUOTE_TOTAL" = "18000" ] && ok "business fare quote total = 180.00 CNY" || bad "business fare quote total wrong ($BUSINESS_QUOTE_TOTAL)"
[ "$BUSINESS_QUOTE_RULE_SET" = "$BUSINESS_RULE_SET" ] && ok "business fare quote uses business rule set" || bad "business fare quote used $BUSINESS_QUOTE_RULE_SET"
quote_total_with_retry "" "$TVL_RULE" "$SEG_RULE" STANDARD_RECHECK_TOTAL STANDARD_RECHECK_RULE_SET
[ "$STANDARD_RECHECK_TOTAL" = "12000" ] && ok "standard fare unaffected by business publish" || bad "standard fare changed ($STANDARD_RECHECK_TOTAL)"
[ "$STANDARD_RECHECK_RULE_SET" = "$RULE_SET" ] && ok "standard fare still uses standard rule set" || bad "standard fare used $STANDARD_RECHECK_RULE_SET"
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

# What this step is actually for: proving the managed refund_fee rule (3000)
# published above is the one fare-pricing applied, rather than the default set's
# 2000. That shows up as the refund waterfall's penalty BASE -- base_fare 12000
# minus refund_fee 3000 = 9000 -- not as refundableAmount.
#
# refundableAmount cannot be asserted here. This traveler is an ADULT and the
# reason is CUSTOMER_REQUEST, so RefundPolicyEngine classifies it VOLUNTARY, and
# every voluntary tier charges a penalty; only INVOLUNTARY_OVERRIDE and
# STUDENT_GT_2D_FREE are zero-penalty. The assertion here used to be
# `refundableAmount == 9000`, which silently demanded a zero penalty and so
# could never pass for this scenario -- it was reading the penalty base and
# calling it the refund. It failed before the hardcoded departure date was made
# relative too; that change only altered which tier it lost in.
#
# The detail lives on the case, not on the evaluate response: evaluateResponse
# returns only caseId/eligible/adjustmentQuoteId/refundableAmount/amountDue
# (PostSalesMapper), while refundAssessment hangs off decision on the GET.
req GET post-sales "/api/v1/post-sales-cases/$CASE_RULE"
check_code 200 "fetch evaluated case"
PENALTY_BASE=$(jget "['decision']['refundAssessment']['penaltyAmount']['minorUnits']")
TIER_APPLIED=$(jget "['decision']['refundAssessment']['tierApplied']")
REFUND_RULE=$(jget "['decision']['refundableAmount']['minorUnits']")
[ "$PENALTY_BASE" = "9000" ] \
  && ok "managed refund_fee applied: penalty base = 90.00 CNY (120.00 - 30.00)" \
  || bad "managed refund_fee not applied (penalty base $PENALTY_BASE, expected 9000; tier=$TIER_APPLIED)"
# A voluntary ADULT refund must land on a penalty-charging tier, never on one of
# the two zero-penalty overrides -- if it ever does, the classification broke.
case "$TIER_APPLIED" in
  INVOLUNTARY_OVERRIDE|STUDENT_GT_2D_FREE)
    bad "voluntary ADULT refund took a zero-penalty tier ($TIER_APPLIED)" ;;
  "")
    bad "refund assessment reported no tier" ;;
  *)
    ok "voluntary refund charged a penalty tier ($TIER_APPLIED, refundable=$REFUND_RULE)" ;;
esac

echo "== 5. restore default pricing (supersede back so later suite runs keep 107.50/8750)"
RESTORE_VERSION="e2e-restore-$(date +%s)"
# The default supplier-default/contract-default set is the baseline every other
# e2e script and the loadgen quote against, so it gets a deliberately longer
# term (2 years) than the per-test sets above: it must outlive not just this run
# but every later run against a cluster that is never re-seeded.
RESTORE_ENDS_AT="$(iso_days 730)"
req POST fare-pricing /api/v1/fare-rule-sets "{\"supplierId\":\"supplier-default\",\"contractId\":\"contract-default\",\"productCode\":\"rail-standard\",\"mode\":\"rail\",\"channel\":\"WEB\",\"version\":\"$RESTORE_VERSION\",\"effectiveWindow\":{\"startsAt\":\"$WINDOW_STARTS_AT\",\"endsAt\":\"$RESTORE_ENDS_AT\"},\"rules\":[{\"ruleId\":\"base\",\"kind\":\"base_fare\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":10000},\"explanation\":{\"code\":\"fare.base\",\"parameters\":{\"rule\":\"base\"}},\"refundable\":true},{\"ruleId\":\"tax\",\"kind\":\"tax\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":750},\"explanation\":{\"code\":\"fare.tax\",\"parameters\":{\"rule\":\"tax\"}},\"refundable\":true},{\"ruleId\":\"refund-fee\",\"kind\":\"refund_fee\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":2000},\"explanation\":{\"code\":\"fare.refund_fee\",\"parameters\":{\"rule\":\"refund-fee\"}},\"refundable\":true},{\"ruleId\":\"change-fee\",\"kind\":\"change_fee\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":1500},\"explanation\":{\"code\":\"fare.change_fee\",\"parameters\":{\"rule\":\"change-fee\"}},\"refundable\":true}]}"
check_code 201 "create restore rule set"
RESTORE_ID=$(jget "['ruleSetId']")
req POST fare-pricing "/api/v1/fare-rule-sets/$RESTORE_ID/publish" '{}'
check_code 200 "publish restore rule set"
sleep 2
req POST fare-pricing /api/v1/fare-quotes "{\"travelerRefs\":[\"$TVL_RULE\"],\"channel\":\"WEB\",\"segmentRefs\":[\"$SEG_RULE\"]}"
RESTORED_TOTAL=$(jget "['breakdown']['total']['minorUnits']")
[ "$RESTORED_TOTAL" = "10750" ] && ok "default pricing restored (107.50)" || bad "restore failed (total=$RESTORED_TOTAL)"

summary
