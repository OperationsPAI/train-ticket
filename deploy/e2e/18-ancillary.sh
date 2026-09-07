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

create_real_order() { # -> sets REAL_ORDER/REAL_ACCT/REAL_TVL (CREATED state is cancellable)
  REAL_ACCT="acc-$(uuid7)"; create_traveler "$REAL_ACCT" ANC; REAL_TVL=$CREATED_TRAVELER
  seed_segment "GANC${RANDOM}"
  find_itinerary_for_segment "$REAL_TVL" "$SEEDED_SEG"
  req POST fare-pricing /api/v1/fare-quotes "{\"travelerRefs\":[\"$REAL_TVL\"],\"channel\":\"WEB\",\"segmentRefs\":[\"$SEEDED_SEG\"]}"
  check_code 201 "real-order quote"
  sleep 3
  req POST offer-management /api/v1/offers "{\"accountId\":\"$REAL_ACCT\",\"channelId\":\"WEB\",\"itineraryRef\":\"$FOUND_ITIN\",\"travelerRefs\":[\"$REAL_TVL\"]}"
  check_code 201 "real-order offer"
  local offer offerv
  offer=$(jget "['offerId']"); offerv=$(jget "['offerVersion']")
  req POST journey-order /api/v1/journey-orders "{\"accountId\":\"$REAL_ACCT\",\"offerId\":\"$offer\",\"offerVersion\":${offerv:-1},\"travelerRefs\":[\"$REAL_TVL\"],\"segmentRefs\":[\"$SEEDED_SEG\"]}"
  check_code 201 "real order created"
  REAL_ORDER=$(jget "['orderId']")
}

create_published_catalog() { post /api/v1/ancillary-catalog-items "$(catalog_body ${1:-SEGMENT} ${2:-true})"; check_code 201 "create catalog"; CATALOG=$(jget "['catalogItemId']"); VER=$(jget "['version']"); post "/api/v1/ancillary-catalog-items/$CATALOG/publish" "{\"approvalRef\":\"apr-e2e\",\"expectedVersion\":$VER}"; check_code 200 "publish catalog"; }
quote_select() { local order=$1; post /api/v1/ancillary-offers "{\"catalogItemId\":\"$CATALOG\",\"journeyOrderId\":\"$order\",\"travelerRef\":\"tvl-e2e\",\"segmentRef\":\"seg-e2e\",\"entitlementRef\":\"ent-e2e\",\"departureAt\":\"$(future_ts 6)\",\"quantity\":1}"; check_code 201 "draft offer"; OFFER=$(jget "['ancillaryOfferId']"); O_VER=$(jget "['offerVersion']"); post "/api/v1/ancillary-offers/$OFFER/quote" "{\"expectedVersion\":$O_VER,\"validitySeconds\":600}"; check_code 200 "quote offer"; O_VER=$(jget "['offerVersion']"); post "/api/v1/ancillary-offers/$OFFER/select" "{\"journeyOrderId\":\"$order\",\"expectedVersion\":$O_VER}"; check_code 201 "select offer"; ITEM=$(jget "['ancillaryOrderItemId']"); }

seed_corridor
create_published_catalog
quote_select "jo-anc-$(uuid7)"
post "/api/v1/ancillary-order-items/$ITEM/confirm" '{"reasonCode":"SUPPLIER_PENDING"}'; check_code 200 "pending confirmation"
post "/api/v1/ancillary-order-items/$ITEM/confirm" '{"confirmationRef":"conf-e2e"}'; check_code 200 "confirmed"
post "/api/v1/ancillary-order-items/$ITEM/fulfillment-ready" '{"providerRef":"voucher-e2e"}'; check_code 200 "fulfillment ready"
post "/api/v1/ancillary-order-items/$ITEM/fulfillment-facts" "{\"factType\":\"MEAL_ISSUED\",\"occurredAt\":\"$(future_ts 0)\",\"performedBy\":\"PROVIDER\",\"idempotencyRef\":\"meal-$(uuid7)\"}"; check_code 200 "record fulfilled fact"; [ "$(jget "['status']")" = FULFILLED ] && ok "item fulfilled" || bad "item not fulfilled"
quote_select "jo-cancel-$(uuid7)"; post "/api/v1/ancillary-order-items/$ITEM/cancel" '{"reasonCode":"USER_CANCEL","source":"USER"}'; check_code 200 "cancel chain"; [ "$(jget "['status']")" = CANCELLED ] && ok "cancelled" || bad "cancel status"
post "/api/v1/ancillary-order-items/$ITEM/refund-suggestions" '{"reasonCode":"USER_CANCEL"}'; check_code 200 "refund suggestion"; [ "$(jget "['recommendation']")" = FULL_REFUND ] && ok "full refund suggested" || bad "refund suggestion"
post "/api/v1/ancillary-order-items/$ITEM/refunded" '{"refundRef":"rf-e2e","refundedAmount":{"currency":"CNY","minorUnits":1800},"refundedAt":"'"$(future_ts 0)"'"}'; check_code 200 "record refunded"

# Linkage must ride a REAL producer event: fabricating envelopes onto
# another context's stream spoofs the producer and poisons real consumers
# (a synthetic slim payload FATALed notification at the wave-17 gate).
create_real_order
quote_select "$REAL_ORDER"
req POST journey-order "/api/v1/journey-orders/$REAL_ORDER/cancel" '{"reason":"CUSTOMER_CHANGED_PLANS"}'
check_code 200 "cancel real main order"
for i in $(seq 1 20); do get "/api/v1/ancillary-order-items/$ITEM"; [ "$(jget "['status']")" = CANCELLED ] && break; sleep 1; done
[ "$(jget "['status']")" = CANCELLED ] && ok "main-order cancel event linkage" || bad "linked cancel status $(jget "['status']")"

post /api/v1/ancillary-offers "{\"catalogItemId\":\"$CATALOG\",\"travelerRef\":\"tvl-exp\",\"segmentRef\":\"seg-exp\",\"entitlementRef\":\"ent-exp\",\"departureAt\":\"$(future_ts 6)\",\"quantity\":1}"; check_code 201 "draft expiring offer"; EXP_OFFER=$(jget "['ancillaryOfferId']"); EXP_VER=$(jget "['offerVersion']"); post "/api/v1/ancillary-offers/$EXP_OFFER/quote" "{\"expectedVersion\":$EXP_VER,\"validitySeconds\":1}"; check_code 200 "quote expiring offer"; for i in $(seq 1 10); do sleep 1; get "/api/v1/ancillary-offers/$EXP_OFFER"; [ "$(jget "['status']")" = EXPIRED ] && break; done; [ "$(jget "['status']")" = EXPIRED ] && ok "offer expired" || bad "offer expiry"

post /api/v1/ancillary-offers "{\"catalogItemId\":\"$CATALOG\",\"travelerRef\":\"tvl-bad\",\"segmentRef\":\"seg-bad\",\"entitlementRef\":\"ent-bad\",\"departureAt\":\"$(future_ts 1)\",\"quantity\":1}"; check_code 201 "draft ineligible"; [ "$(jget "['status']")" = INELIGIBLE ] && ok "time-window rejected" || bad "eligibility not rejected"

summary
