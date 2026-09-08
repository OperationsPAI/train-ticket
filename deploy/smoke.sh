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
# All lists are derived from the rendered Helm release. Nothing here hardcodes
# a count or a name.

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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

# --------------------------------------------------------------------------
# Expected sets, derived from the rendered release.
#
# Rendered rather than read from values: this must assert against the exact
# bytes helm installs, so a templating bug that ships a Service the values did
# not describe -- or drops one they did -- is caught rather than agreed with.
# --------------------------------------------------------------------------
RENDERED=$("${ROOT_DIR}/deploy/render-manifests.sh" 2>/dev/null) || {
  echo "smoke: could not render the Helm release; cannot derive what to check" >&2
  exit 2
}

mapfile -t EXPECTED_SERVICES < <(
  printf '%s' "$RENDERED" | python3 -c "
import sys, yaml
docs = [d for d in yaml.safe_load_all(sys.stdin) if d]
names = set()
for d in docs:
    if d.get('kind') != 'Service':
        continue
    # ExternalName Services are DNS aliases with no selector, so the API server
    # never creates an Endpoints object for them and the readiness check below
    # would report every one of them as broken. The chart has one: the bare
    # \`postgres\` alias pointing at the default shard. Skipping it loses no
    # coverage -- the shard it aliases is checked on its own.
    if d.get('spec', {}).get('type') == 'ExternalName':
        continue
    names.add(d['metadata']['name'])
for name in sorted(names):
    print(name)
"
)

if [ "${#EXPECTED_SERVICES[@]}" -eq 0 ]; then
  echo "smoke: derived an empty expectation from the rendered release; refusing to pass vacuously" >&2
  exit 2
fi
echo "smoke: expecting ${#EXPECTED_SERVICES[@]} services (derived from the rendered Helm release)"

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
#    Delegated to deploy/verify-databases.sh rather than reimplemented: that
#    script already derives the expectation per shard from the rendered DSNs, so
#    a second weaker copy here (which assumed a single `postgres` host) would
#    silently stop checking anything the moment a shard was added.
#
#    Independent of the bootstrap hook on purpose: this asserts the outcome, so
#    it still catches a hook that was skipped or silently no-opped.
# --------------------------------------------------------------------------
section "databases present"
if KCTX="$KCTX" NAMESPACE="$NS" "${ROOT_DIR}/deploy/verify-databases.sh" >/tmp/smoke-dbs.$$ 2>&1; then
  pass "$(grep -o 'OK -- .*' /tmp/smoke-dbs.$$ || echo 'all required databases exist')"
else
  fail "required databases are missing"
  sed 's/^/    /' /tmp/smoke-dbs.$$ >&2
fi
rm -f /tmp/smoke-dbs.$$

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

# Idempotency-Key must be a UUID v7 -- the services reject anything else with
# 400 VALIDATION_FAILED. This step used to send "smoke-<n>-<epoch>", so the
# end-to-end write never actually ran: it failed validation before reaching a
# handler, meaning the one check here that proves the stack (rather than its
# parts) had never passed. Same generator as deploy/e2e/lib.sh's uuid7.
IDEM_KEY=$(python3 - <<'PY'
import time, random
ms = int(time.time() * 1000)
rest = (7 << 76) | (random.getrandbits(12) << 64) | (2 << 62) | random.getrandbits(62)
b = ms.to_bytes(6, 'big') + rest.to_bytes(10, 'big')
h = b.hex()
print(f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}")
PY
)

if ! k run "$SMOKE_POD" --image=curlimages/curl:8.10.1 --restart=Never --command -- sleep 120 >/dev/null 2>&1; then
  fail "could not start smoke pod"
elif ! k wait --for=condition=Ready "pod/$SMOKE_POD" --timeout=90s >/dev/null 2>&1; then
  fail "smoke pod did not become ready"
else
  out=$(k exec -i "$SMOKE_POD" -- curl -sS -m 30 -w $'\n%{http_code}' \
    -X POST "http://place-network:8080/api/v1/places" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${IDEM_KEY}" \
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
      #
      # The shard is read from place-network's own DATABASE_URL rather than
      # assumed to be `postgres`: the bare name is now an ExternalName alias
      # (no pods, so `exec deploy/postgres` cannot work), and once shards are
      # split this service may not live on the default one.
      pg_host=$(printf '%s' "$RENDERED" | python3 -c "
import sys, yaml, re
for d in yaml.safe_load_all(sys.stdin):
    if not d or d.get('kind') != 'Deployment' or d['metadata']['name'] != 'place-network':
        continue
    for c in d['spec']['template']['spec']['containers']:
        for e in c.get('env') or []:
            if e.get('name') == 'DATABASE_URL':
                m = re.search(r'@([^:/]+):', e.get('value', ''))
                if m:
                    print(m.group(1))
" 2>/dev/null)
      if [ -z "$pg_host" ]; then
        fail "could not determine place-network's postgres shard from the rendered release"
      else
        rows=$(k exec "deploy/${pg_host}" -- psql -U trainticket -d place_network -tAc \
          "SELECT count(*) FROM place_snapshots WHERE id = '${place_id}'" 2>/dev/null | tr -d ' \r')
        [ "${rows:-0}" = "1" ] && pass "place ${place_id} is persisted in postgres (${pg_host}/place_network.place_snapshots)" \
          || fail "place ${place_id} is NOT in postgres (found '${rows:-0}' rows) -- service is not durably writing"
      fi
    fi
  else
    fail "place-network write returned ${code}, expected 201: $(printf '%s' "$bodyout" | head -c 200)"
  fi
fi

# --------------------------------------------------------------------------
section "result"
if [ "$FAILURES" -eq 0 ]; then
  echo "SMOKE PASSED: ${#EXPECTED_SERVICES[@]} services ready, all required databases present, end-to-end write durable."
  exit 0
fi
echo "SMOKE FAILED: ${FAILURES} check(s) failed." >&2
exit 1
