# Shared helpers for e2e chain scripts. Source this file.
# Requires: kubectl context kind-arl-test, namespace train-ticket,
# a long-lived curl pod named e2e-curl (created on demand).
set -uo pipefail

NS=train-ticket
KCTX=kind-arl-test
REDIS_POD=""

k() { kubectl --context "$KCTX" -n "$NS" "$@"; }

ensure_curl_pod() {
  # A finite sleep or an eviction leaves the pod in Completed/Failed, where
  # `k wait` can only time out — recreate unless it is actually alive.
  local phase
  phase=$(k get pod e2e-curl -o jsonpath='{.status.phase}' 2>/dev/null || true)
  case "$phase" in
    Running|Pending) ;;
    *)
      k delete pod e2e-curl --ignore-not-found --wait=true >/dev/null 2>&1
      k run e2e-curl --image=curlimages/curl:8.10.1 --restart=Never --command -- sleep infinity >/dev/null
      ;;
  esac
  k wait --for=condition=Ready pod/e2e-curl --timeout=60s >/dev/null
}

redis_pod() {
  [ -n "$REDIS_POD" ] || REDIS_POD=$(k get pods -l app=redis --no-headers -o custom-columns=:metadata.name 2>/dev/null | head -1)
  [ -n "$REDIS_POD" ] || REDIS_POD=$(k get pods --no-headers | awk '/^redis/{print $1; exit}')
  echo "$REDIS_POD"
}

uuid7() {
  python3 - << 'PY'
import time, random
ms = int(time.time()*1000)
rest = (7 << 76) | (random.getrandbits(12) << 64) | (2 << 62) | random.getrandbits(62)
b = ms.to_bytes(6,'big') + rest.to_bytes(10,'big')
h = b.hex(); print(f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}")
PY
}

# req METHOD SERVICE PATH BODY -> sets RESP (body) and LAST_CODE globals
LAST_CODE=""
RESP=""
req() {
  local m=$1 s=$2 p=$3 body=${4:-}
  local out
  out=$(k exec -i e2e-curl -- curl -s -w $'\n%{http_code}' -X "$m" "http://$s:8080$p" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(uuid7)" \
    ${body:+-d "$body"} 2>/dev/null)
  LAST_CODE=$(echo "$out" | tail -1)
  RESP=$(echo "$out" | sed '$d')
}

# jget FIELD_EXPR -> extract from $RESP, e.g. jget "['placeId']"
jget() { echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d$1)" 2>/dev/null; }

xlen() { k exec "$(redis_pod)" -- redis-cli XLEN "$1" 2>/dev/null; }

# last_event STREAM [N] -> pretty-print last N envelopes' type+id
last_events() {
  local st=$1 n=${2:-3}
  k exec "$(redis_pod)" -- redis-cli --no-raw XREVRANGE "$st" + - COUNT "$n" 2>/dev/null \
    | grep -o '{.*}' | while read -r line; do
        echo "$line" | python3 -c "
import sys, json
try:
    e = json.loads(sys.stdin.read().encode().decode('unicode_escape'))
    print(f\"  {e.get('eventType')} corr={e.get('correlationId','')[:22]} payload_keys={sorted(e.get('payload',{}).keys())[:6]}\")
except Exception as ex:
    print('  <unparsable>', ex)"
      done
}

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✓ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✗ $1"; }
check_code() { # expected description
  if [ "$LAST_CODE" = "$1" ]; then ok "$2 [$LAST_CODE]"; else bad "$2 [got $LAST_CODE want $1]"; fi
}
summary() { echo "== RESULT pass=$PASS fail=$FAIL"; [ "$FAIL" -eq 0 ]; }
