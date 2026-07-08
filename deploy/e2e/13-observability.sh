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

tracez_contains_span_signal() {
  printf '%s' "$RESP" | python3 -c '
import re
import sys
body = sys.stdin.read()
if "POST /api/v1/itineraries/search" in body or "/api/v1/itineraries/search" in body:
    sys.exit(0)
for match in re.finditer(r"(?:Spans|Span Count|Total Spans|Error Spans)</td>\s*<td[^>]*>\s*([0-9]+)", body, re.IGNORECASE):
    if int(match.group(1)) > 0:
        sys.exit(0)
for match in re.finditer(r">([1-9][0-9]*)</td>", body):
    if int(match.group(1)) > 0:
        sys.exit(0)
sys.exit(1)
'
}

echo "== 1. otel collector health"
curl_raw http://otel-collector:13133/
if [ "$LAST_CODE" = "200" ]; then ok "otel-collector health HTTP 200"; else bad "otel-collector health HTTP got $LAST_CODE"; fi

echo "== 2. zpages tracez reachable"
curl_raw http://otel-collector:55679/debug/tracez
if [ "$LAST_CODE" = "200" ]; then ok "zpages tracez HTTP 200"; else bad "zpages tracez HTTP got $LAST_CODE"; fi
if [ -n "$RESP" ]; then ok "zpages tracez body non-empty"; else bad "zpages tracez body is empty"; fi

echo "== 3. trigger trip-planning request"
api_req POST trip-planning /api/v1/itineraries/search '{"originRef":"obs-origin","destinationRef":"obs-destination","departureDate":"2026-08-01","travelerRefs":["obs-traveler"],"channel":"WEB"}'
check_code 200 "search itineraries for trace smoke"

SPAN_FOUND=0
for attempt in 1 2 3 4 5 6; do
  sleep 2
  curl_raw http://otel-collector:55679/debug/tracez
  if [ "$LAST_CODE" = "200" ] && [ -n "$RESP" ] && tracez_contains_span_signal; then
    SPAN_FOUND=1
    break
  fi
done
if [ "$SPAN_FOUND" = "1" ]; then ok "zpages tracez shows span activity"; else bad "zpages tracez did not show span activity after search"; fi

summary
