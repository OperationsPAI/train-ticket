#!/usr/bin/env bash
# Account gate chain: registered account can buy, frozen account is rejected, unfreeze restores purchasing.
# Requires 01-seed.sh to have run (reads .refs.env).
cd "$(dirname "$0")" && . ./lib.sh
. ./.refs.env
ensure_curl_pod

ACCT="acct_$(uuid7)"

create_offer_for_account() {
  local account_id=$1
  local suffix=$2
  local traveler itinerary segment quote offer version

  req POST traveler-profile /api/v1/travelers "{\"accountId\":\"$account_id\",\"travelerType\":\"ADULT\",\"givenName\":\"Account\",\"familyName\":\"Gate$suffix\"}"
  check_code 201 "register traveler $suffix"
  traveler=$(jget "['travelerId']")
  verify_traveler "$traveler"
  sleep 3

  req POST trip-planning /api/v1/itineraries/search "{\"originRef\":\"$P_BJ\",\"destinationRef\":\"$P_SH\",\"departureDate\":\"${SERVICE_DATE:-$JOURNEY_DATE}\",\"travelerRefs\":[\"$traveler\"],\"channel\":\"WEB\"}"
  check_code 200 "search itinerary $suffix"
  itinerary=$(jget "['itineraries'][0]['itineraryRef']")
  segment=$(jget "['itineraries'][0]['legs'][0]['serviceSegmentRef']")

  req POST fare-pricing /api/v1/fare-quotes "{\"travelerRefs\":[\"$traveler\"],\"channel\":\"WEB\",\"segmentRefs\":[\"$segment\"]}"
  check_code 201 "create fare quote $suffix"
  quote=$(jget "['quoteId']")
  echo "  quote=$quote traveler=$traveler segment=$segment"
  sleep 3

  req POST offer-management /api/v1/offers "{\"accountId\":\"$account_id\",\"channelId\":\"WEB\",\"itineraryRef\":\"$itinerary\",\"travelerRefs\":[\"$traveler\"]}"
  check_code 201 "create offer $suffix"
  offer=$(jget "['offerId']")
  version=$(jget "['offerVersion']")

  OFFER_ID=$offer
  OFFER_VERSION=${version:-1}
  TRAVELER_REF=$traveler
  SEGMENT_REF=$segment
}

create_order_for_current_offer() {
  local account_id=$1
  req POST journey-order /api/v1/journey-orders "{\"accountId\":\"$account_id\",\"offerId\":\"$OFFER_ID\",\"offerVersion\":$OFFER_VERSION,\"travelerRefs\":[\"$TRAVELER_REF\"],\"segmentRefs\":[\"$SEGMENT_REF\"]}"
}

wait_for_account_event_projection() {
  local account_id=$1
  local expected_code=$2
  local description=$3
  local attempt
  for attempt in 1 2 3 4 5 6 7 8; do
    create_offer_for_account "$account_id" "projection-$attempt"
    create_order_for_current_offer "$account_id"
    [ "$LAST_CODE" = "$expected_code" ] && break
    echo "  projection wait attempt $attempt got $LAST_CODE, retrying..."
    sleep 4
  done
  check_code "$expected_code" "$description"
}

echo "== 0. register account"
req POST account /api/v1/accounts "{\"accountId\":\"$ACCT\"}"
check_code 201 "create account"
echo "  ACCT=$ACCT"
sleep 4

echo "== 1. normal purchase is allowed"
create_offer_for_account "$ACCT" "initial"
create_order_for_current_offer "$ACCT"
check_code 201 "create journey order before freeze"
ORDER_BEFORE=$(jget "['orderId']")
echo "  ORDER_BEFORE=$ORDER_BEFORE"

echo "== 2. freeze account and wait until journey-order projection rejects new orders"
req POST account "/api/v1/accounts/$ACCT/freeze" '{"reason":"account gate e2e","operator":"e2e"}'
check_code 200 "freeze account"
wait_for_account_event_projection "$ACCT" 422 "new journey order rejected while frozen"
REJECT_CODE=$(jget "['code']")
[ "$REJECT_CODE" = "DOMAIN_RULE_VIOLATION" ] && ok "frozen rejection error code DOMAIN_RULE_VIOLATION" || bad "frozen rejection code $REJECT_CODE"

echo "== 3. unfreeze account and wait until purchases are allowed again"
req POST account "/api/v1/accounts/$ACCT/unfreeze" '{"reason":"account gate e2e resolved"}'
check_code 200 "unfreeze account"
wait_for_account_event_projection "$ACCT" 201 "create journey order after unfreeze"
ORDER_AFTER=$(jget "['orderId']")
echo "  ORDER_AFTER=$ORDER_AFTER"

summary
