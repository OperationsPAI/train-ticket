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
req POST post-sales "/api/v1/post-sales-cases/$CASE/evaluate" '{}'
check_code 200 "evaluate case"
echo "  eligible=$(jget "['eligible']") refundable=$(jget "['refundableAmount']")"

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
req GET post-sales "/api/v1/post-sales-cases/$CASE"
echo "  case status: $(jget "['status']") [$LAST_CODE]"
req GET journey-order "/api/v1/journey-orders/$ORDER"
echo "  order status: $(jget "['status']") [$LAST_CODE]"
req GET entitlement-ticketing "/api/v1/entitlements/$ENT"
echo "  entitlement status: $(jget "['status']") [$LAST_CODE]"
summary
