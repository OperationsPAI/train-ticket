#!/usr/bin/env bash
# Ancillary Service e2e: catalog, offer/order lifecycle, refund, cancellation, expiry, eligibility.
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

post() { req POST ancillary-service "$1" "$2"; }
get() { req GET ancillary-service "$1"; }
future_ts() { python3 - "$1" <<'PY'
import datetime,sys
print((datetime.datetime.now(datetime.UTC)+datetime.timedelta(hours=int(sys.argv[1]))).isoformat().replace('+00:00','Z'))
PY
}
catalog_body() { local scope=${1:-SEGMENT} reqseg=${2:-true}; echo "{\"serviceType\":\"MEAL\",\"displayName\":\"E2E meal $(uuid7)\",\"attachmentScope\":\"$scope\",\"modalities\":[\"TRAIN\"],\"price\":{\"currency\":\"CNY\",\"minorUnits\":1800},\"salesWindow\":{\"startAt\":\"$(future_ts -1)\",\"endAt\":\"$(future_ts 24)\"},\"purchaseCutoffHoursBeforeDeparture\":2,\"eligibilityRuleVersion\":\"min-v1\",\"requiresEntitlementRef\":true,\"requiresSegmentRef\":$reqseg,\"fulfillmentMethod\":\"VOUCHER\"}"; }
create_published_catalog() { post /api/v1/ancillary-catalog-items "$(catalog_body ${1:-SEGMENT} ${2:-true})"; check_code 201 "create catalog"; CATALOG=$(jget "['catalogItemId']"); VER=$(jget "['version']"); post "/api/v1/ancillary-catalog-items/$CATALOG/publish" "{\"approvalRef\":\"apr-e2e\",\"expectedVersion\":$VER}"; check_code 200 "publish catalog"; }
quote_select() { local order=$1; post /api/v1/ancillary-offers "{\"catalogItemId\":\"$CATALOG\",\"journeyOrderId\":\"$order\",\"travelerRef\":\"tvl-e2e\",\"segmentRef\":\"seg-e2e\",\"entitlementRef\":\"ent-e2e\",\"departureAt\":\"$(future_ts 6)\",\"quantity\":1}"; check_code 201 "draft offer"; OFFER=$(jget "['ancillaryOfferId']"); O_VER=$(jget "['offerVersion']"); post "/api/v1/ancillary-offers/$OFFER/quote" "{\"expectedVersion\":$O_VER,\"validitySeconds\":600}"; check_code 200 "quote offer"; O_VER=$(jget "['offerVersion']"); post "/api/v1/ancillary-offers/$OFFER/select" "{\"journeyOrderId\":\"$order\",\"expectedVersion\":$O_VER}"; check_code 201 "select offer"; ITEM=$(jget "['ancillaryOrderItemId']"); }

create_published_catalog
quote_select "jo-anc-$(uuid7)"
post "/api/v1/ancillary-order-items/$ITEM/confirm" '{"reasonCode":"SUPPLIER_PENDING"}'; check_code 200 "pending confirmation"
post "/api/v1/ancillary-order-items/$ITEM/confirm" '{"confirmationRef":"conf-e2e"}'; check_code 200 "confirmed"
post "/api/v1/ancillary-order-items/$ITEM/fulfillment-ready" '{"providerRef":"voucher-e2e"}'; check_code 200 "fulfillment ready"
post "/api/v1/ancillary-order-items/$ITEM/fulfillment-facts" "{\"factType\":\"MEAL_ISSUED\",\"occurredAt\":\"$(future_ts 0)\",\"performedBy\":\"PROVIDER\",\"idempotencyRef\":\"meal-$(uuid7)\"}"; check_code 200 "record fulfilled fact"; [ "$(jget "['status']")" = FULFILLED ] && ok "item fulfilled" || bad "item not fulfilled"
quote_select "jo-cancel-$(uuid7)"; post "/api/v1/ancillary-order-items/$ITEM/cancel" '{"reasonCode":"USER_CANCEL","source":"USER"}'; check_code 200 "cancel chain"; [ "$(jget "['status']")" = CANCELLED ] && ok "cancelled" || bad "cancel status"
post "/api/v1/ancillary-order-items/$ITEM/refund-suggestions" '{"reasonCode":"USER_CANCEL"}'; check_code 200 "refund suggestion"; [ "$(jget "['recommendation']")" = FULL_REFUND ] && ok "full refund suggested" || bad "refund suggestion"
post "/api/v1/ancillary-order-items/$ITEM/refunded" '{"refundRef":"rf-e2e","refundedAmount":{"currency":"CNY","minorUnits":1800},"refundedAt":"'"$(future_ts 0)"'"}'; check_code 200 "record refunded"

MAIN_ORDER="jo-main-cancel-$(uuid7)"; quote_select "$MAIN_ORDER"
EVT_ID="evt-$(uuid7)"; CORR="corr-$(uuid7)"; ENV=$(python3 - "$EVT_ID" "$CORR" "$MAIN_ORDER" <<'PY'
import datetime,json,sys
print(json.dumps({"eventId":sys.argv[1],"eventType":"JourneyOrderCancelled","schemaVersion":1,"producer":"journey-order","correlationId":sys.argv[2],"occurredAt":datetime.datetime.now(datetime.UTC).isoformat().replace('+00:00','Z'),"payload":{"orderId":sys.argv[3],"reason":"E2E"}}))
PY
)
k exec "$(redis_pod)" -- redis-cli XADD events:journey-order '*' envelope "$ENV" >/dev/null
for i in $(seq 1 20); do get "/api/v1/ancillary-order-items/$ITEM"; [ "$(jget "['status']")" = CANCELLED ] && break; sleep 1; done
[ "$(jget "['status']")" = CANCELLED ] && ok "main-order cancel event linkage" || bad "linked cancel status $(jget "['status']")"

post /api/v1/ancillary-offers "{\"catalogItemId\":\"$CATALOG\",\"travelerRef\":\"tvl-exp\",\"segmentRef\":\"seg-exp\",\"entitlementRef\":\"ent-exp\",\"departureAt\":\"$(future_ts 6)\",\"quantity\":1}"; check_code 201 "draft expiring offer"; EXP_OFFER=$(jget "['ancillaryOfferId']"); EXP_VER=$(jget "['offerVersion']"); post "/api/v1/ancillary-offers/$EXP_OFFER/quote" "{\"expectedVersion\":$EXP_VER,\"validitySeconds\":1}"; check_code 200 "quote expiring offer"; for i in $(seq 1 10); do sleep 1; get "/api/v1/ancillary-offers/$EXP_OFFER"; [ "$(jget "['status']")" = EXPIRED ] && break; done; [ "$(jget "['status']")" = EXPIRED ] && ok "offer expired" || bad "offer expiry"

post /api/v1/ancillary-offers "{\"catalogItemId\":\"$CATALOG\",\"travelerRef\":\"tvl-bad\",\"segmentRef\":\"seg-bad\",\"entitlementRef\":\"ent-bad\",\"departureAt\":\"$(future_ts 1)\",\"quantity\":1}"; check_code 201 "draft ineligible"; [ "$(jget "['status']")" = INELIGIBLE ] && ok "time-window rejected" || bad "eligibility not rejected"

summary
