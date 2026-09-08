#!/usr/bin/env bash
# 退票 chain: post-sales case (REFUND) → evaluate → approve → event fan-out
# (payment refund, capacity release, entitlement void) → PostSalesApplied →
# order adjusted. Requires 02-purchase.sh refs (.refs.env).
cd "$(dirname "$0")" && . ./lib.sh
. ./.refs.env
ensure_curl_pod

echo "== 1. open REFUND case for $ORDER"
req POST post-sales /api/v1/post-sales-cases "{\"journeyOrderId\":\"$ORDER\",\"caseType\":\"REFUND\",\"scope\":{\"orderItemRefs\":[\"$SB\"],\"segmentRefs\":[\"$SEG\"],\"travelerRefs\":[\"$TVL\"],\"entitlementRefs\":[\"$ENT\"]},\"reasonCode\":\"CUSTOMER_REQUEST\",\"actorRef\":\"$ACCT\"}"
check_code 201 "open post-sales case"
CASE=$(jget "['caseId']")
echo "  CASE=$CASE status=$(jget "['status']")"

echo "== 2. evaluate"
req_retry_conflict POST post-sales "/api/v1/post-sales-cases/$CASE/evaluate" '{}'
check_code 200 "evaluate case"
REFUNDABLE=$(jget "['refundableAmount']['minorUnits']")
echo "  eligible=$(jget "['eligible']") refundable=$(jget "['refundableAmount']")"
# DERIVED from the order's own fare, not hardcoded.
#
# This asserted a flat 9500 with the comment "Order fare 100.00", which was true
# only while journey-order priced every order at a literal 100.00 regardless of
# the offer. Orders now carry the offer's real total, so the same 5% penalty on a
# 107.50 fare refunds 10212 -- the assertion was measuring the placeholder.
#
# The segment departs ~30 days out, so RefundPolicyEngine applies
# TIER_GT_15_DAYS: a 5% penalty on the order's payable total.
req GET journey-order "/api/v1/journey-orders/$ORDER"
check_code 200 "fetch order for its fare"
# payableTotal is a Money object on the HTTP resource, not a bare number -- the
# internal DTO flattens it to minor units but the API does not.
ORDER_TOTAL=$(jget "['monetarySummary']['payableTotal']['minorUnits']")
case "$ORDER_TOTAL" in
  ''|*[!0-9]*) ORDER_TOTAL="" ;;
esac
if [ -z "$ORDER_TOTAL" ]; then
  bad "could not read the order's payable total; cannot derive the expected refund"
else
  EXPECTED_REFUND=$(python3 -c "t=$ORDER_TOTAL; print(t - round(t*5/100))")
  [ "$REFUNDABLE" = "$EXPECTED_REFUND" ] \
    && ok "refundable = $REFUNDABLE (5% TIER_GT_15_DAYS off the order's $ORDER_TOTAL)" \
    || bad "refundable amount wrong ($REFUNDABLE, expected $EXPECTED_REFUND = 95% of $ORDER_TOTAL)"
fi

echo "== 3. approve"
req POST post-sales "/api/v1/post-sales-cases/$CASE/approve" '{}'
check_code 200 "approve case"
sleep 6

echo "== 4. event fan-out"
echo "  post-sales stream:"; last_events events:post-sales 3
echo "  payment stream:"; last_events events:payment 2
echo "  capacity stream:"; last_events events:capacity-availability 2
echo "  entitlement stream:"; last_events events:entitlement-ticketing 2

echo "== 5. final states"
CASE_STATUS=""
# APPLIED waits on CapacityReleased from the prompt EntitlementVoided-driven
# release path; this should complete within 30s.
for attempt in 1 2 3 4 5 6; do
  req GET post-sales "/api/v1/post-sales-cases/$CASE"
  CASE_STATUS=$(jget "['status']")
  [ "$CASE_STATUS" = "APPLIED" ] && break
  sleep 5
done
echo "  case status: $CASE_STATUS [$LAST_CODE]"
[ "$CASE_STATUS" = "APPLIED" ] && ok "case APPLIED" || bad "case not applied ($CASE_STATUS)"
ORDER_STATUS=""
for attempt in 1 2 3; do
  req GET journey-order "/api/v1/journey-orders/$ORDER"
  ORDER_STATUS=$(jget "['status']")
  [ "$ORDER_STATUS" = "ADJUSTED" ] && break
  sleep 4
done
echo "  order status: $ORDER_STATUS [$LAST_CODE]"
[ "$ORDER_STATUS" = "ADJUSTED" ] && ok "order ADJUSTED" || bad "order not adjusted ($ORDER_STATUS)"
req GET entitlement-ticketing "/api/v1/entitlements/$ENT"
ENT_STATUS=$(jget "['status']")
echo "  entitlement status: $ENT_STATUS [$LAST_CODE]"
[ "$ENT_STATUS" = "VOIDED" ] && ok "entitlement VOIDED" || bad "entitlement not voided ($ENT_STATUS)"

echo "== 6. bystander side-effects (notification / finance-settlement / reporting)"
stream_mentions() { # STREAM EVENT_TYPE NEEDLE -> yes/empty
  k exec "$(redis_pod)" -- redis-cli XREVRANGE "$1" + - COUNT 60 > /tmp/bystander.txt 2>/dev/null
  ET="$2" NEEDLE="$3" python3 - << 'PYEX'
import re, os, json
raw = open("/tmp/bystander.txt").read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e = json.loads(m.group(0).encode().decode('unicode_escape'))
        if e.get("eventType") == os.environ["ET"] and os.environ["NEEDLE"] in json.dumps(e.get("payload", {})):
            print("yes"); break
    except Exception:
        pass
PYEX
}
NOTIF_OK=$(stream_mentions events:notification NotificationScheduled "$TVL")
[ "$NOTIF_OK" = "yes" ] && ok "notification scheduled for our traveler" || bad "no NotificationScheduled for traveler"
FIN_OK=$(stream_mentions events:finance-settlement RevenueRecognized "$ORDER")
[ "$FIN_OK" = "yes" ] && ok "RevenueRecognized for our order" || bad "no RevenueRecognized mentioning order"
RECON_OK=$(stream_mentions events:finance-settlement ReconciliationCompleted "$ORDER")
[ "$RECON_OK" = "yes" ] && ok "ReconciliationCompleted for our order" || bad "no ReconciliationCompleted mentioning order"
# Same derivation as the evaluate assertion above: the reversal must match the
# refunded amount, which follows the order's real fare rather than a placeholder.
REDUCTION_OK=$(stream_mentions events:finance-settlement RevenueRecognitionReversed "\"minorUnits\": ${EXPECTED_REFUND:-9500}")
[ "$REDUCTION_OK" = "yes" ] \
  && ok "refund revenue reversal published for ${EXPECTED_REFUND:-9500}" \
  || bad "no revenue reversal for ${EXPECTED_REFUND:-9500}"
invoice_event_count() {
  k exec "$(redis_pod)" -- redis-cli XREVRANGE events:finance-settlement + - COUNT 100 > /tmp/invoice-events.txt 2>/dev/null
  ORDER_REF="$ORDER" python3 - << 'PYEX'
import re, os, json
count = 0
raw = open("/tmp/invoice-events.txt").read()
for m in re.finditer(r'\{.*\}', raw):
    try:
        e = json.loads(m.group(0).encode().decode('unicode_escape'))
        if e.get("eventType") == "InvoiceGenerated" and e.get("payload", {}).get("orderId") == os.environ["ORDER_REF"]:
            count += 1
    except Exception:
        pass
print(count)
PYEX
}
generate_invoice_fixed_key() {
  local key=$1 body="{\"orderId\":\"$ORDER\"}" out
  out=$(k exec -i e2e-curl -- curl -s -w $'\n%{http_code}' -X POST "http://finance-settlement:8080/api/v1/invoices" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $key" \
    -d "$body" 2>/dev/null)
  LAST_CODE=$(echo "$out" | tail -1)
  RESP=$(echo "$out" | sed '$d')
}
INV_EVT_BEFORE=$(invoice_event_count)
INVOICE_KEY=$(uuid7)
generate_invoice_fixed_key "$INVOICE_KEY"
INV_CODE_1=$LAST_CODE; INV_ID=$(jget "['invoiceId']")
generate_invoice_fixed_key "$INVOICE_KEY"
INV_CODE_2=$LAST_CODE; INV_ID_REPLAY=$(jget "['invoiceId']")
INV_EVT_AFTER=$(invoice_event_count)
INV_EVT_DELTA=$((INV_EVT_AFTER - INV_EVT_BEFORE))
[ "$INV_CODE_1" = "201" ] && [ "$INV_CODE_2" = "201" ] && [ -n "$INV_ID" ] && [ "$INV_ID" = "$INV_ID_REPLAY" ] && [ "$INV_EVT_DELTA" -eq 1 ] && ok "GenerateInvoice fixed-key replay returns same invoice without duplicate event" || bad "GenerateInvoice replay failed ($INV_CODE_1/$INV_CODE_2 $INV_ID/$INV_ID_REPLAY events+$INV_EVT_DELTA)"
INV_EVT_OK=$(stream_mentions events:finance-settlement InvoiceGenerated "$ORDER")
[ "$INV_EVT_OK" = "yes" ] && ok "InvoiceGenerated for our order" || bad "no InvoiceGenerated mentioning order"
RPT_LEN=$(xlen events:reporting)
[ "${RPT_LEN:-0}" -gt 0 ] 2>/dev/null && ok "reporting published ReadModelRebuilt facts (XLEN=$RPT_LEN)" || bad "reporting stream empty"
req GET reporting /api/v1/dashboards/dash-revenue
DASH_STATUS=$(jget "['status']")
REBUILDS=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('currentSnapshot',{}).get('rebuildId',''))" 2>/dev/null)
echo "  dash-revenue status=$DASH_STATUS snapshot=$REBUILDS"
case "$DASH_STATUS" in ready|stale) ok "dash-revenue read model live" ;; *) bad "dash-revenue status unexpected ($DASH_STATUS)" ;; esac
summary
