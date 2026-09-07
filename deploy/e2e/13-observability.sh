#!/usr/bin/env bash
# Observability smoke test: collector health, zpages, and coarse span visibility.
cd "$(dirname "$0")" && . ./lib.sh
ensure_curl_pod

curl_raw() {
  local url=$1 out
  out=$(k exec -i e2e-curl -- curl -sS --connect-timeout 2 --max-time 5 -w $'\n%{http_code}' "$url" 2>/dev/null || printf '\n000')
  LAST_CODE=$(echo "$out" | tail -1)
  RESP=$(echo "$out" | sed '$d')
}

api_req() {
  local method=$1 service=$2 path=$3 body=${4:-} out
  out=$(k exec -i e2e-curl -- curl -sS --connect-timeout 2 --max-time 10 -w $'\n%{http_code}' -X "$method" "http://$service:8080$path" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(uuid7)" \
    ${body:+-d "$body"} 2>/dev/null || printf '\n000')
  LAST_CODE=$(echo "$out" | tail -1)
  RESP=$(echo "$out" | sed '$d')
}

accepted_spans_total() {
  # Sum of otelcol_receiver_accepted_spans across transports; empty on error.
  printf '%s' "$RESP" | awk '/^otelcol_receiver_accepted_spans/ { sum += $NF } END { if (NR > 0) printf "%.0f", sum }'
}

echo "== 1. otel collector health"
curl_raw http://otel-collector:13133/
if [ "$LAST_CODE" = "200" ]; then ok "otel-collector health HTTP 200"; else bad "otel-collector health HTTP got $LAST_CODE"; fi

echo "== 2. zpages tracez reachable"
curl_raw http://otel-collector:55679/debug/tracez
if [ "$LAST_CODE" = "200" ]; then ok "zpages tracez HTTP 200"; else bad "zpages tracez HTTP got $LAST_CODE"; fi
if [ -n "$RESP" ]; then ok "zpages tracez body non-empty"; else bad "zpages tracez body is empty"; fi

echo "== 3. span flow: receiver counter must grow after real traffic"
curl_raw http://otel-collector:8888/metrics
BEFORE=$(accepted_spans_total)
if [ "$LAST_CODE" = "200" ] && [ -n "$BEFORE" ]; then ok "collector metrics endpoint reports accepted spans ($BEFORE)"; else bad "collector metrics endpoint unavailable (HTTP $LAST_CODE)"; BEFORE=0; fi

api_req POST trip-planning /api/v1/itineraries/search '{"originRef":"obs-origin","destinationRef":"obs-destination","departureDate":"'"$JOURNEY_DATE"'","travelerRefs":["obs-traveler"],"channel":"WEB"}'
check_code 200 "search itineraries for trace smoke"

SPAN_FOUND=0
for attempt in 1 2 3 4 5 6 7 8; do
  sleep 2
  curl_raw http://otel-collector:8888/metrics
  AFTER=$(accepted_spans_total)
  if [ "$LAST_CODE" = "200" ] && [ -n "$AFTER" ] && [ "$AFTER" -gt "$BEFORE" ] 2>/dev/null; then
    SPAN_FOUND=1
    break
  fi
done
if [ "$SPAN_FOUND" = "1" ]; then ok "accepted-span counter grew after search ($BEFORE -> $AFTER)"; else bad "accepted-span counter did not grow after search (before=$BEFORE after=${AFTER:-n/a})"; fi

summary
