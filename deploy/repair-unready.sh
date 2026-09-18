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
# Only the actually-unready deployments are restarted, and only the ones this
# repository builds. A blanket `rollout restart -l
# app.kubernetes.io/part-of=train-ticket` would bounce the databases and the
# telemetry store, which carry the same label, and it would make an
# already-healthy re-deploy slow. On a healthy cluster this script restarts
# nothing and returns in seconds -- that is the idempotency story.

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NS="${NAMESPACE:-train-ticket}"
# Default to the selected context, not a hardcoded cluster name.
KCTX="${KCTX:-$(kubectl config current-context 2>/dev/null || echo '')}"
TIMEOUT="${ROLLOUT_TIMEOUT:-300s}"
# Which workloads this may restart, DERIVED: a directory in deploy/docker with a
# Dockerfile in it is a thing this repository builds, and nothing else is.
#
# It used to be a list of infrastructure names to skip, and a skip list drifts in
# the direction that hurts: `jaeger` stayed in it after the template was deleted,
# while `clickhouse`, `prometheus`, `otel-cluster` and `kube-state-metrics` were
# never added -- so an unready telemetry store would have been restarted, cutting
# the window it was recording. Naming what may be touched cannot fail that way,
# because a new infrastructure component is absent from the list by default.
BUILT_DIR="${ROOT_DIR}/deploy/docker"
MAX_ROUNDS="${MAX_ROUNDS:-2}"
# How many restart rounds to attempt before giving up. A service can need one
# restart; needing three means something is actually broken, not racing.

k() {
  if [ -n "$KCTX" ]; then
    kubectl --context "$KCTX" -n "$NS" "$@"
  else
    kubectl -n "$NS" "$@"
  fi
}

is_ours() {
  [ -f "${BUILT_DIR}/$1/Dockerfile" ]
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
    if ! is_ours "$dep"; then
      echo "repair: ${dep} is not ready but is not built here; not restarting it"
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
