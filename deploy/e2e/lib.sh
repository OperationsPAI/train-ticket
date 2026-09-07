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

# --- Relative-time helpers ---------------------------------------------------
# Every validity/effective window seeded by these scripts MUST be derived from
# these helpers instead of an absolute literal. The services resolve windows
# against the real wall clock (datetime.now(UTC)), and these scripts seed the
# LIVE cluster, so a hardcoded date expires both the e2e suite and the seeded
# production data on a fixed calendar day -- the failure mode that took out the
# fare-pricing tests. Implemented with python3 (already a hard dependency here
# for uuid7/jget) to avoid the GNU-vs-BSD `date -d` portability split.
iso_offset() { # <seconds> -> RFC3339 UTC at now+seconds (negative allowed)
  python3 - "$1" << 'PY'
import sys, datetime
print((datetime.datetime.now(datetime.UTC)+datetime.timedelta(seconds=int(sys.argv[1]))).strftime('%Y-%m-%dT%H:%M:%SZ'))
PY
}
iso_days() { # <days> -> RFC3339 UTC at now+days
  python3 - "$1" << 'PY'
import sys, datetime
print((datetime.datetime.now(datetime.UTC)+datetime.timedelta(days=int(sys.argv[1]))).strftime('%Y-%m-%dT%H:%M:%SZ'))
PY
}
date_plus() { # <YYYY-MM-DD> <days> -> that date shifted by N days
  python3 - "$1" "$2" << 'PY'
import sys, datetime
base = datetime.date.fromisoformat(sys.argv[1])
print((base + datetime.timedelta(days=int(sys.argv[2]))).isoformat())
PY
}
date_days() { # <days> -> YYYY-MM-DD at now+days
  python3 - "$1" << 'PY'
import sys, datetime
print((datetime.datetime.now(datetime.UTC)+datetime.timedelta(days=int(sys.argv[1]))).strftime('%Y-%m-%d'))
PY
}
year_days() { # <days> -> YYYY at now+days
  python3 - "$1" << 'PY'
import sys, datetime
print((datetime.datetime.now(datetime.UTC)+datetime.timedelta(days=int(sys.argv[1]))).strftime('%Y'))
PY
}

# Shared window sizes, so every script expresses the same intent.
# A national ID document is long-lived; 5 years outlives any cluster.
CREDENTIAL_VALID_UNTIL="$(iso_days 1825)"
# Travel is booked in advance; 30 days out is a normal advance purchase and is
# comfortably inside every effective window seeded below.
JOURNEY_DATE="$(date_days 30)"
# Windows open 30 days in the past so they are already effective regardless of
# clock skew between this runner and the cluster.
WINDOW_STARTS_AT="$(iso_days -30)"
# A supplier fare contract runs about a commercial year. Long enough that a
# cluster left up for months keeps quoting, short enough to express intent.
WINDOW_ENDS_AT="$(iso_days 365)"


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

# Real-name verification helper (ADR-0003 wave A): registers an ID_CARD
# credential with a SIM pass-tail and opens a verification case that the
# deterministic gateway PASSES. Call after creating any traveler that will
# purchase. Usage: verify_traveler <travelerRef>
verify_traveler() {
  local tvl=$1
  local doc="1101011990010105$((RANDOM % 900 + 100))5" # tail 5 => SIM pass (<=5)
  local dochash
  dochash="$(printf '%s' "$doc" | sha256sum | cut -c1-64)${doc: -1}" # SIM reads the appended tail digit
  req POST identity-verification /api/v1/identity-verification/credentials "{\"travelerId\":\"$tvl\",\"profileSnapshotVersion\":\"snap-1\",\"documentType\":\"ID_CARD\",\"maskedDocumentNo\":\"110***********${doc: -2}\",\"documentHash\":\"$dochash\",\"canonicalNameHash\":\"name-$tvl\",\"validUntil\":\"$CREDENTIAL_VALID_UNTIL\"}"
  local cred
  cred=$(jget "['credentialRecordId']")
  local fp
  fp=$(python3 - "$tvl" "$dochash" "$CREDENTIAL_VALID_UNTIL" <<'PY'
import hashlib, sys
parts = [f"name-{sys.argv[1]}", "ID_CARD", sys.argv[2], "", sys.argv[3], "", "snap-1"]
print(hashlib.sha256("|".join(parts).encode()).hexdigest())
PY
)
  req POST identity-verification /api/v1/identity-verification/verification-cases "{\"travelerId\":\"$tvl\",\"credentialRecordId\":\"$cred\",\"purpose\":\"ORDER_CREATION\",\"materialFingerprint\":\"$fp\",\"simPolicyVersion\":\"sim-tail-v1\",\"requestedAt\":\"$(iso_offset 0)\"}"
  [ "$(jget "['status']")" = PASSED ] || bad "verify_traveler $tvl status $(jget "['status']")"
}
