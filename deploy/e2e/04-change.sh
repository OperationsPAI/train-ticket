#!/usr/bin/env bash
# 改签 chain: CHANGE case on the current order (evaluate exercises
# fare-pricing adjustment quotes) → approve → old entitlement voided +
# capacity released → rebook = fresh purchase for the new travel intent.
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

echo "== 0. fresh purchase to change"
bash ./02-purchase.sh > /tmp/change-base.log 2>&1
grep -q 'fail=0' <(grep '== RESULT' /tmp/change-base.log | tail -1) && ok "base purchase green" || bad "base purchase failed"
. ./.refs.env

echo "== 1. open CHANGE case for $ORDER"
req POST post-sales /api/v1/post-sales-cases "{\"journeyOrderId\":\"$ORDER\",\"caseType\":\"CHANGE\",\"scope\":{\"orderItemRefs\":[\"$SB\"],\"segmentRefs\":[\"$SEG\"],\"travelerRefs\":[\"$TVL\"],\"entitlementRefs\":[\"$ENT\"]},\"reasonCode\":\"SCHEDULE_CHANGE\",\"actorRef\":\"$ACCT\"}"
check_code 201 "open CHANGE case"
CASE=$(jget "['caseId']")
echo "  CASE=$CASE"

echo "== 2. evaluate (fare adjustment quote path)"
req_retry_conflict POST post-sales "/api/v1/post-sales-cases/$CASE/evaluate" '{}'
check_code 200 "evaluate CHANGE"
AMOUNT_DUE=$(jget "['amountDue']['minorUnits']")
echo "  eligible=$(jget "['eligible']") amountDue=$(jget "['amountDue']")"
# same-fare change: fare diff 0 + change fee 15.00 CNY (default rule set)
[ "$AMOUNT_DUE" = "1500" ] && ok "amountDue = 15.00 CNY change fee (real adjustment quote)" || bad "amountDue wrong ($AMOUNT_DUE, expected 1500)"

echo "== 3. approve → old ticket teardown"
req POST post-sales "/api/v1/post-sales-cases/$CASE/approve" '{}'
check_code 200 "approve CHANGE"
CASE_STATUS=""
for attempt in 1 2 3 4; do
  sleep 6
  req GET post-sales "/api/v1/post-sales-cases/$CASE"
  CASE_STATUS=$(jget "['status']")
  [ "$CASE_STATUS" = "APPLIED" ] && break
done
req GET entitlement-ticketing "/api/v1/entitlements/$ENT"
ENT_STATUS=$(jget "['status']")
echo "  case=$CASE_STATUS entitlement=$ENT_STATUS"
[ "$CASE_STATUS" = "APPLIED" ] && ok "CHANGE case applied" || bad "CHANGE case not applied ($CASE_STATUS)"
[ "$ENT_STATUS" = "VOIDED" ] && ok "old entitlement voided" || bad "old entitlement not voided ($ENT_STATUS)"

echo "== 4. rebook: fresh purchase on the released capacity"
bash ./02-purchase.sh > /tmp/rebook.log 2>&1
NEW_RESULT=$(grep '== RESULT' /tmp/rebook.log | tail -1)
NEW_ORDER=$(grep 'ORDER=' /tmp/rebook.log | grep -o 'ord-[a-f0-9-]*' | head -1)
echo "  rebook: $NEW_RESULT (order $NEW_ORDER)"
echo "$NEW_RESULT" | grep -q 'fail=0' && ok "rebook purchase green" || bad "rebook purchase had failures"
summary
