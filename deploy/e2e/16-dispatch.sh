#!/usr/bin/env bash
# Dispatch e2e: normal, driver reassign, user cancel, no-show, mutual exclusion, idempotency.
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

ride_req_key() { local key=$1 body=$2 out; out=$(k exec -i e2e-curl -- curl -s -w $'\n%{http_code}' -X POST "http://dispatch:8080/api/v1/ride-requests" -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "$body" 2>/dev/null); LAST_CODE=$(echo "$out"|tail -1); RESP=$(echo "$out"|sed '$d'); }
ops_post() { local key=$1 path=$2 body=${3:-'{}'} out; out=$(k exec -i e2e-curl -- curl -s -w $'\n%{http_code}' -X POST "http://dispatch:8080$path" -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "$body" 2>/dev/null); LAST_CODE=$(echo "$out"|tail -1); RESP=$(echo "$out"|sed '$d'); }
future_window() { python3 - <<'PY'
import datetime,json
now=datetime.datetime.now(datetime.UTC)
print(json.dumps({"startAt":(now+datetime.timedelta(minutes=2)).strftime('%Y-%m-%dT%H:%M:%SZ'),"endAt":(now+datetime.timedelta(minutes=32)).strftime('%Y-%m-%dT%H:%M:%SZ')}))
PY
}
body_for() { local suffix=$1 fp=$2 tw; tw=$(future_window); echo "{\"pickupRef\":\"plc-dispatch-pick-$suffix\",\"dropoffRef\":\"plc-dispatch-drop-$suffix\",\"timeWindow\":$tw,\"riderAccountId\":\"acc-dispatch-$suffix\",\"travelerRef\":\"tvl-dispatch-$suffix\",\"estimatedFareRef\":\"fare-est-$suffix\",\"intentFingerprint\":\"$fp\"}"; }

# normal chain
BODY=$(body_for normal "intent-normal-$(uuid7)"); KEY=$(uuid7); ride_req_key "$KEY" "$BODY"; check_code 201 "create normal ride"; RR=$(jget "['rideRequestId']"); [ "$(jget "['status']")" = MATCHING ] && ok "request auto-matches" || bad "request not MATCHING"
ride_req_key "$KEY" "$BODY"; [ "$LAST_CODE" = 201 ] && [ "$(jget "['rideRequestId']")" = "$RR" ] && ok "create idempotent replay" || bad "create replay failed"
ops_post $(uuid7) "/api/v1/ride-requests/$RR/assign" '{"driverRef":"drv-a","vehicleRef":"veh-a","etaSeconds":120}'; check_code 200 "assign normal"
ops_post $(uuid7) "/api/v1/ride-requests/$RR/eta" '{"etaSeconds":60}'; check_code 200 "eta normal"
ops_post $(uuid7) "/api/v1/ride-requests/$RR/driver-arrived" '{}'; check_code 200 "arrived normal"
ops_post $(uuid7) "/api/v1/ride-requests/$RR/start" '{}'; check_code 200 "start normal"
ops_post $(uuid7) "/api/v1/ride-requests/$RR/complete" '{"finalFareRef":"fare-final-a"}'; check_code 200 "complete normal"; [ "$(jget "['status']")" = COMPLETED ] && ok "normal completed" || bad "normal status $(jget "['status']")"

# driver cancel reassign
BODY=$(body_for reassign "intent-reassign-$(uuid7)"); ride_req_key $(uuid7) "$BODY"; check_code 201 "create reassign ride"; RR2=$(jget "['rideRequestId']")
ops_post $(uuid7) "/api/v1/ride-requests/$RR2/assign" '{"driverRef":"drv-b","vehicleRef":"veh-b","etaSeconds":80}'; check_code 200 "assign before driver cancel"
ops_post $(uuid7) "/api/v1/ride-requests/$RR2/driver-cancel" '{"reason":"DRIVER_UNAVAILABLE"}'; check_code 200 "driver cancel"; [ "$(jget "['status']")" = MATCHING ] && ok "driver cancel returns MATCHING" || bad "driver cancel status"
ops_post $(uuid7) "/api/v1/ride-requests/$RR2/assign" '{"driverRef":"drv-c","vehicleRef":"veh-c","etaSeconds":70}'; check_code 200 "reassign after driver cancel"

# user cancel
BODY=$(body_for usercancel "intent-usercancel-$(uuid7)"); ride_req_key $(uuid7) "$BODY"; check_code 201 "create user cancel ride"; RR3=$(jget "['rideRequestId']")
ops_post $(uuid7) "/api/v1/ride-requests/$RR3/user-cancel" '{"reason":"USER_CHANGED_PLANS"}'; check_code 200 "user cancel"; [ "$(jget "['status']")" = USER_CANCELLED ] && ok "user cancelled rests terminal" || bad "user cancel status"

# no-show
BODY=$(body_for noshow "intent-noshow-$(uuid7)"); ride_req_key $(uuid7) "$BODY"; check_code 201 "create no-show ride"; RR4=$(jget "['rideRequestId']")
ops_post $(uuid7) "/api/v1/ride-requests/$RR4/assign" '{"driverRef":"drv-d","vehicleRef":"veh-d","etaSeconds":30}'; check_code 200 "assign no-show"
ops_post $(uuid7) "/api/v1/ride-requests/$RR4/driver-arrived" '{}'; check_code 200 "arrived no-show"
ops_post $(uuid7) "/api/v1/ride-requests/$RR4/no-show" '{"reason":"RIDER_ABSENT"}'; check_code 200 "record no-show"; [ "$(jget "['status']")" = NO_SHOW ] && ok "no-show terminal" || bad "no-show status"

# mutual exclusion while active, idempotent assign replay
FP="intent-conflict-$(uuid7)"; BODY=$(body_for conflict "$FP"); K=$(uuid7); ride_req_key "$K" "$BODY"; check_code 201 "create conflict ride"; RR5=$(jget "['rideRequestId']")
ride_req_key $(uuid7) "$BODY"; [ "$LAST_CODE" = 409 ] && ok "active duplicate rejected" || bad "active duplicate code $LAST_CODE"
AK=$(uuid7); AB='{"driverRef":"drv-e","vehicleRef":"veh-e","etaSeconds":55}'; ops_post "$AK" "/api/v1/ride-requests/$RR5/assign" "$AB"; check_code 200 "assign for replay"; AID=$(jget "['assignment']['rideAssignmentId']")
ops_post "$AK" "/api/v1/ride-requests/$RR5/assign" "$AB"; [ "$LAST_CODE" = 200 ] && [ "$(jget "['assignment']['rideAssignmentId']")" = "$AID" ] && ok "ops idempotent replay" || bad "ops replay failed"

summary
