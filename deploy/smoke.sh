#!/usr/bin/env bash
#
# Post-deploy smoke verification. Fails loudly and non-zero.
#
# WHY `kubectl rollout status` IS NOT ENOUGH
# ------------------------------------------
# Today four services were Running and passing liveness while permanently 503
# on readiness, and one service (group-booking) reported healthy for an unknown
# length of time while its database did not exist -- its readiness check simply
# did not touch Postgres. Rollout status and pod phase are both blind to that
# class of failure, so this script checks three independent things:
#
#   1. Every Service the manifest declares has at least one READY endpoint.
#      This is the check that catches "Running but 503 on readiness": an
#      unready pod is removed from Endpoints, so it is invisible to `get pods`
#      -grade checks but fatal to any caller.
#   2. Every database the manifest requires exists AND the service can be
#      reached. Catches the group_booking class directly.
#   3. A real cross-service write actually works end to end.
#
# Checks 1 and 2 are structural and fast. Check 3 is the one that proves the
# stack, not just its parts.
#
# All lists are derived from deploy/k8s/services.yaml. Nothing here hardcodes
# a count or a name.

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="${MANIFEST:-${ROOT_DIR}/deploy/k8s/services.yaml}"
NS="${NAMESPACE:-train-ticket}"
KCTX="${KCTX:-$(kubectl config current-context 2>/dev/null || echo '')}"
TIMEOUT="${SMOKE_TIMEOUT:-180}"

k() {
  if [ -n "$KCTX" ]; then
    kubectl --context "$KCTX" -n "$NS" "$@"
  else
    kubectl -n "$NS" "$@"
  fi
}

FAILURES=0
pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1" >&2; FAILURES=$((FAILURES + 1)); }
section() { printf '\n== %s\n' "$1"; }

command -v kubectl >/dev/null || { echo "smoke: kubectl not found" >&2; exit 2; }
k get ns "$NS" >/dev/null 2>&1 || { echo "smoke: namespace ${NS} not found in context ${KCTX}" >&2; exit 2; }
[ -r "$MANIFEST" ] || { echo "smoke: cannot read ${MANIFEST}" >&2; exit 2; }

# --------------------------------------------------------------------------
# Expected sets, derived from the manifest.
# --------------------------------------------------------------------------
mapfile -t EXPECTED_SERVICES < <(
  python3 - "$MANIFEST" <<'PY'
import sys, yaml
docs = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
for name in sorted({d["metadata"]["name"] for d in docs if d.get("kind") == "Service"}):
    print(name)
PY
)
mapfile -t EXPECTED_DBS < <(grep -oE '@postgres:[0-9]+/[A-Za-z0-9_]+' "$MANIFEST" | cut -d/ -f2 | sort -u)

if [ "${#EXPECTED_SERVICES[@]}" -eq 0 ] || [ "${#EXPECTED_DBS[@]}" -eq 0 ]; then
  echo "smoke: derived an empty expectation from ${MANIFEST}; refusing to pass vacuously" >&2
  exit 2
fi
echo "smoke: expecting ${#EXPECTED_SERVICES[@]} services and ${#EXPECTED_DBS[@]} databases (derived from $(basename "$MANIFEST"))"

# --------------------------------------------------------------------------
# 1. Readiness, measured at Endpoints rather than pod phase.
#    Retried as a whole: right after a rollout some pods legitimately need a
#    probe period or two, and failing on the first sample would make the step
#    flaky rather than meaningful.
# --------------------------------------------------------------------------
section "service endpoints ready"
deadline=$((SECONDS + TIMEOUT))
while :; do
  NOT_READY=()
  ready_json=$(k get endpoints -o json 2>/dev/null)
  for svc in "${EXPECTED_SERVICES[@]}"; do
    n=$(printf '%s' "$ready_json" | python3 -c "
import sys, json
want = sys.argv[1]
data = json.load(sys.stdin)
for e in data.get('items', []):
    if e['metadata']['name'] == want:
        print(sum(len(s.get('addresses') or []) for s in (e.get('subsets') or [])))
        break
else:
    print(0)
" "$svc" 2>/dev/null || echo 0)
    [ "${n:-0}" -gt 0 ] || NOT_READY+=("$svc")
  done
  [ "${#NOT_READY[@]}" -eq 0 ] && break
  if [ "$SECONDS" -ge "$deadline" ]; then break; fi
  sleep 5
done

if [ "${#NOT_READY[@]}" -eq 0 ]; then
  pass "all ${#EXPECTED_SERVICES[@]} services have a ready endpoint"
else
  for svc in "${NOT_READY[@]}"; do
    # The reason a pod is unready is the actionable part, so surface it.
    reason=$(k get pods -l "app.kubernetes.io/name=${svc}" \
      -o jsonpath='{range .items[*]}{.status.phase}{" restarts="}{.status.containerStatuses[0].restartCount}{" "}{end}' 2>/dev/null)
    fail "service ${svc} has no ready endpoint (${reason:-no pods})"
  done
  echo "  hint: kubectl -n ${NS} logs -l app.kubernetes.io/name=<service> --tail=50" >&2
fi

# --------------------------------------------------------------------------
# 2. Every required database exists.
#    Independent of the bootstrap Job on purpose: this asserts the outcome, so
#    it still catches a bootstrap that was skipped, silently no-opped, or ran
#    against a stale manifest.
# --------------------------------------------------------------------------
section "databases present"
if ! actual_dbs=$(k exec deploy/postgres -- psql -U trainticket -d postgres -tAc \
    'SELECT datname FROM pg_database' 2>/dev/null | tr -d '\r' | sort); then
  fail "could not query postgres for its database list"
else
  missing=$(comm -23 <(printf '%s\n' "${EXPECTED_DBS[@]}") <(printf '%s\n' "$actual_dbs"))
  if [ -z "$missing" ]; then
    pass "all ${#EXPECTED_DBS[@]} required databases exist"
  else
    while IFS= read -r db; do
      [ -n "$db" ] && fail "database '${db}' is missing (owning service will fail on connect)"
    done <<<"$missing"
    echo "  hint: kubectl -n ${NS} logs job/db-bootstrap" >&2
  fi
fi

# --------------------------------------------------------------------------
# 3. A real end-to-end write.
#    Deliberately exercises a chain rather than one service: creating a place
#    in place-network persists to Postgres, emits an event through the Redis
#    outbox, and the read-back proves the write is durable and queryable. A
#    service that is "ready" but cannot reach its database fails here.
# --------------------------------------------------------------------------
section "end-to-end write"
# `kubectl get --raw` cannot POST, so the write goes through a short-lived pod.
# curlimages/curl is what deploy/e2e/lib.sh already uses in this cluster.
SMOKE_POD="smoke-verify-$$"
cleanup() { k delete pod "$SMOKE_POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true; }
trap cleanup EXIT

CODE_SUFFIX=$(od -An -N2 -tu2 </dev/urandom | tr -d ' ')
SMOKE_BODY=$(printf '{"canonicalName":"Smoke %s","placeType":"CITY","code":"S%02d","timezone":"Asia/Shanghai"}' \
  "$CODE_SUFFIX" "$((CODE_SUFFIX % 100))")

if ! k run "$SMOKE_POD" --image=curlimages/curl:8.10.1 --restart=Never --command -- sleep 120 >/dev/null 2>&1; then
  fail "could not start smoke pod"
elif ! k wait --for=condition=Ready "pod/$SMOKE_POD" --timeout=90s >/dev/null 2>&1; then
  fail "smoke pod did not become ready"
else
  out=$(k exec -i "$SMOKE_POD" -- curl -sS -m 30 -w $'\n%{http_code}' \
    -X POST "http://place-network:8080/api/v1/places" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: smoke-${CODE_SUFFIX}-$(date +%s)" \
    -d "$SMOKE_BODY" 2>/dev/null)
  code=$(printf '%s' "$out" | tail -1)
  bodyout=$(printf '%s' "$out" | sed '$d')

  if [ "$code" = "201" ]; then
    pass "place-network accepted a write [201]"
    place_id=$(printf '%s' "$bodyout" | python3 -c "import sys,json; print(json.load(sys.stdin).get('placeId',''))" 2>/dev/null)
    if [ -z "$place_id" ]; then
      fail "write succeeded but returned no placeId"
    else
      # Read back through a *fresh* request. If the service is serving from a
      # per-process cache but failing to persist, this still passes -- so the
      # durability assertion below is the one that matters.
      rb=$(k exec -i "$SMOKE_POD" -- curl -sS -m 30 -o /dev/null -w '%{http_code}' \
        "http://place-network:8080/api/v1/places/${place_id}" 2>/dev/null)
      [ "$rb" = "200" ] && pass "wrote place ${place_id} and read it back [200]" \
        || fail "read-back of ${place_id} returned ${rb}, expected 200"

      # Durability: the row must actually be in Postgres, not just in memory.
      # This is precisely the assertion that a schema-less or unreachable
      # database defeats, and it is why the smoke step is not just an HTTP 200.
      rows=$(k exec deploy/postgres -- psql -U trainticket -d place_network -tAc \
        "SELECT count(*) FROM place_snapshots WHERE id = '${place_id}'" 2>/dev/null | tr -d ' \r')
      [ "${rows:-0}" = "1" ] && pass "place ${place_id} is persisted in postgres (place_network.place_snapshots)" \
        || fail "place ${place_id} is NOT in postgres (found '${rows:-0}' rows) -- service is not durably writing"
    fi
  else
    fail "place-network write returned ${code}, expected 201: $(printf '%s' "$bodyout" | head -c 200)"
  fi
fi

# --------------------------------------------------------------------------
section "result"
if [ "$FAILURES" -eq 0 ]; then
  echo "SMOKE PASSED: ${#EXPECTED_SERVICES[@]} services ready, ${#EXPECTED_DBS[@]} databases present, end-to-end write durable."
  exit 0
fi
echo "SMOKE FAILED: ${FAILURES} check(s) failed." >&2
exit 1
