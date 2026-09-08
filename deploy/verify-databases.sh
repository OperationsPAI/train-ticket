#!/usr/bin/env bash
#
# Verify that every database the Helm release's services are configured to use
# actually exists, per Postgres shard.
#
# WHY THIS IS SEPARATE FROM CREATING THEM
# ---------------------------------------
# `/docker-entrypoint-initdb.d` runs ONLY when PGDATA is empty, and the chart
# mounts a PersistentVolumeClaim. So on any cluster whose volume has already
# initialised, adding a database to values has NO effect -- the service starts,
# connects to the server, and fails on its first query. `group_booking` was
# missing that way for an unknown length of time while its pod reported healthy,
# because its readiness probe did not touch Postgres.
#
# This script does not fix that; it makes it loud. It derives the expected set
# from the RENDERED release rather than from values, so what is checked is
# exactly what the services were handed in their DATABASE_URL -- a check against
# values would pass while a templating bug shipped a different DSN.
#
# Shards: the DSN host is the shard Service (postgres-core, postgres-read, ...),
# so grouping by host gives the per-shard expectation for free.

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NS="${NAMESPACE:-train-ticket}"
KCTX="${KCTX:-$(kubectl config current-context 2>/dev/null || echo '')}"
RELEASE="${HELM_RELEASE:-train-ticket}"
CHART="${HELM_CHART:-${ROOT_DIR}/deploy/helm/train-ticket}"
VALUES="${HELM_VALUES:-${ROOT_DIR}/deploy/helm/values-kind.yaml}"

k() {
  if [ -n "$KCTX" ]; then
    kubectl --context "$KCTX" -n "$NS" "$@"
  else
    kubectl -n "$NS" "$@"
  fi
}

helm_ctx() { [ -n "$KCTX" ] && printf '%s' "--kube-context $KCTX"; }

fail=0
note() { printf '  %s\n' "$1"; }
bad() { printf '  MISSING  %s\n' "$1" >&2; fail=1; }

# ── Expected: host -> databases, from the rendered release.
rendered=$(helm template "$RELEASE" "$CHART" -f "$VALUES" --namespace "$NS" 2>/dev/null)
if [ -z "$rendered" ]; then
  echo "verify-databases: helm template produced nothing (chart=$CHART values=$VALUES)" >&2
  exit 1
fi

# grep -o with no match exits 1 under set -e; guarded so an empty result is
# reported as the error it is rather than aborting the script silently. That
# exact failure shape bit the kustomize bootstrap.
dsns=$(printf '%s' "$rendered" | grep -oE '@postgres[a-z-]*:5432/[a-z_]+' | sort -u) || true
if [ -z "$dsns" ]; then
  echo "verify-databases: found no @postgres<shard>:5432/<db> DSNs in the rendered release." >&2
  echo "verify-databases: that means the services were rendered without DATABASE_URL, which is a" >&2
  echo "verify-databases: templating bug, not an empty database list. Refusing to report success." >&2
  exit 1
fi

hosts=$(printf '%s\n' "$dsns" | cut -d: -f1 | tr -d '@' | sort -u)
total=0

for host in $hosts; do
  expected=$(printf '%s\n' "$dsns" | grep "^@${host}:" | cut -d/ -f2 | sort -u)
  count=$(printf '%s\n' "$expected" | grep -c .)
  printf '\n== %s (%s databases expected)\n' "$host" "$count"

  # The shard Service name is the DSN host; resolve it to a workload by label
  # rather than assuming `deployment/<host>`, so a shard rename or a switch to a
  # StatefulSet does not silently break this.
  pod=$(k get pod -l "app.kubernetes.io/name=${host}" \
        -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
  if [ -z "$pod" ]; then
    pod=$(k get pod -o name 2>/dev/null | grep -m1 "pod/${host}-" | cut -d/ -f2)
  fi
  if [ -z "$pod" ]; then
    echo "  could not find a pod for shard ${host}" >&2
    fail=1
    continue
  fi

  actual=$(k exec "$pod" -- psql -U trainticket -tAc \
    'SELECT datname FROM pg_database WHERE datistemplate = false' 2>/dev/null | tr -d ' \r')
  if [ -z "$actual" ]; then
    echo "  could not list databases on ${host} (pod ${pod})" >&2
    fail=1
    continue
  fi

  missing=0
  for db in $expected; do
    if printf '%s\n' "$actual" | grep -qx "$db"; then
      total=$((total + 1))
    else
      bad "${host}/${db}"
      missing=$((missing + 1))
    fi
  done
  [ "$missing" -eq 0 ] && note "all ${count} present"
done

echo
if [ "$fail" -eq 0 ]; then
  echo "verify-databases: OK -- ${total} databases present across $(printf '%s\n' "$hosts" | grep -c .) shard(s)."
  exit 0
fi
cat >&2 <<'MSG'
verify-databases: FAILED -- databases are missing.

The initdb ConfigMap only runs on an empty PGDATA, so on an initialised volume
it cannot create these. Either let the db-bootstrap hook run (helm upgrade), or
create them by hand:

  kubectl exec -n train-ticket deploy/postgres-core -- \
    psql -U trainticket -c "CREATE DATABASE <name>"
MSG
exit 1
