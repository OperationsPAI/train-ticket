#!/usr/bin/env bash
#
# Restart only the deployments that are not fully ready, then wait for the
# whole namespace to converge.
#
# WHY
# ---
# Deployment failure mode #4: a service whose startup migration ran while
# Postgres was briefly unavailable (or before its database existed) stays
# permanently not-ready. Its pod is Running and passes liveness, so nothing
# recovers it -- an operator has to notice and run `kubectl rollout restart`
# by hand. That manual step is what this removes.
#
# Only the actually-unready deployments are restarted, for two reasons: a
# blanket `rollout restart -l app.kubernetes.io/part-of=train-ticket` would
# bounce postgres and redis (they carry the same label), and it would make an
# already-healthy re-deploy slow. On a healthy cluster this script restarts
# nothing and returns in seconds -- that is the idempotency story.

set -uo pipefail

NS="${NAMESPACE:-train-ticket}"
# Default to the selected context, not a hardcoded cluster name.
KCTX="${KCTX:-$(kubectl config current-context 2>/dev/null || echo '')}"
TIMEOUT="${ROLLOUT_TIMEOUT:-300s}"
# Infrastructure is excluded: restarting postgres would defeat the bootstrap
# that just ran, and these are waited on separately before this point.
#
# Matched as a PREFIX, not for equality. Under Helm the Postgres Deployment is
# named per shard (postgres-core, and one per additional shard), so an exact
# comparison against "postgres" stopped matching anything -- the skip list
# silently became empty and this script would have restarted the database.
INFRA_SKIP="${INFRA_SKIP:-postgres redis jaeger mailpit otel-collector}"
# How many restart rounds to attempt before giving up. A service can need one
# restart; needing three means something is actually broken, not racing.
MAX_ROUNDS="${MAX_ROUNDS:-2}"

k() {
  if [ -n "$KCTX" ]; then
    kubectl --context "$KCTX" -n "$NS" "$@"
  else
    kubectl -n "$NS" "$@"
  fi
}

is_infra() {
  local name=$1 skip
  for skip in $INFRA_SKIP; do
    case "$name" in "$skip"|"$skip"-*) return 0 ;; esac
  done
  return 1
}

# Deployments whose ready replica count is below their desired count.
unready_deployments() {
  k get deployments -o json 2>/dev/null | python3 -c "
import sys, json
data = json.load(sys.stdin)
for d in data.get('items', []):
    spec_replicas = d['spec'].get('replicas', 1)
    ready = d.get('status', {}).get('readyReplicas', 0)
    if ready < spec_replicas:
        print(d['metadata']['name'])
"
}

for round in $(seq 1 "$MAX_ROUNDS"); do
  mapfile -t pending < <(unready_deployments)

  targets=()
  for dep in "${pending[@]}"; do
    [ -n "$dep" ] || continue
    if is_infra "$dep"; then
      echo "repair: ${dep} is not ready but is infrastructure; not restarting it here"
      continue
    fi
    targets+=("$dep")
  done

  if [ "${#targets[@]}" -eq 0 ]; then
    echo "repair: nothing to restart (round ${round})"
    break
  fi

  echo "repair: round ${round}, restarting ${#targets[@]} not-ready deployment(s): ${targets[*]}"
  for dep in "${targets[@]}"; do
    k rollout restart "deployment/${dep}" >/dev/null || echo "repair: could not restart ${dep}" >&2
  done
  for dep in "${targets[@]}"; do
    k rollout status "deployment/${dep}" --timeout="$TIMEOUT" >/dev/null 2>&1 \
      || echo "repair: ${dep} did not converge within ${TIMEOUT}" >&2
  done
done

# Authoritative wait over everything, including infrastructure. This is what
# actually gates the deploy; the restarts above are only the repair attempt.
echo "repair: waiting for all deployments in ${NS}"
if k rollout status deployment --timeout="$TIMEOUT" >/dev/null 2>&1; then
  echo "repair: all deployments are available"
  exit 0
fi

echo "repair: the following deployments are still not ready:" >&2
mapfile -t still < <(unready_deployments)
for dep in "${still[@]}"; do
  [ -n "$dep" ] || continue
  echo "  - ${dep}" >&2
done
echo "  hint: kubectl -n ${NS} logs -l app.kubernetes.io/name=<name> --tail=50" >&2
exit 1
