#!/usr/bin/env bash
# Whole-cluster restart certification.
#
# Proves the persistence ruling end to end: pause traffic, drain outboxes,
# snapshot every *_snapshots row count, delete EVERY pod in the namespace
# (postgres keeps its PVC; redis has no volume, so all streams and consumer
# groups are wiped — by design, the DB is the source of truth and Redis is
# transport only), wait for full recovery, prove row counts survived
# unchanged, then re-run the entire 01-11 suite against the reborn cluster.
cd "$(dirname "$0")" && . ./lib.sh

PGUSER=trainticket
SUITE="01-seed.sh 02-purchase.sh 03-refund.sh 04-change.sh 05-fulfillment.sh 06-risk.sh 07-fare-rules.sh 08-notify-support.sh 09-manual-action.sh 10-account-gate.sh 11-legacy-acl.sh 14-waitlist.sh 15-wallet.sh 16-dispatch.sh 17-disruption.sh 18-ancillary.sh 19-transfer.sh 20-seat.sh"

pg_pod() { k get pods -l app.kubernetes.io/name=postgres --no-headers -o custom-columns=:metadata.name 2>/dev/null | head -1; }
pg() { local db=$1 sql=$2; k exec "$PGPOD" -- psql -U "$PGUSER" -d "$db" -Atc "$sql" 2>/dev/null; }

service_dbs() {
  pg postgres "SELECT datname FROM pg_database WHERE datistemplate=false AND datname NOT IN ('postgres','trainticket','gate_conflict_test') ORDER BY 1"
}

# snapshot_counts FILE -> one "db.table=count" line per *_snapshots table
snapshot_counts() {
  local file=$1 db tables q
  : > "$file"
  for db in $(service_dbs); do
    tables=$(pg "$db" "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename LIKE '%\_snapshots' ORDER BY 1")
    [ -n "$tables" ] || continue
    q=$(echo "$tables" | awk -v db="$db" '{ if (NR>1) printf " UNION ALL "; printf "SELECT '\''%s.%s='\'' || count(*) FROM %s", db, $1, $1 }')
    pg "$db" "$q" >> "$file"
  done
  sort -o "$file" "$file"
}

wait_outbox_drained() {
  local attempt pending db
  for attempt in $(seq 1 24); do
    pending=0
    for db in $(service_dbs); do
      pending=$((pending + $(pg "$db" "SELECT count(*) FROM outbox WHERE published_at IS NULL" || echo 0)))
    done
    if [ "$pending" -eq 0 ]; then ok "all outboxes drained"; return 0; fi
    echo "  ... $pending unpublished outbox rows, waiting"
    sleep 5
  done
  bad "outboxes did not drain ($pending rows pending)"
}

echo "== 12-restart: whole-cluster restart certification"

# --- 1. pause traffic ---------------------------------------------------
PGPOD=$(pg_pod)
LG_REPLICAS=$(k get deploy loadgen -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "")
if [ -n "$LG_REPLICAS" ] && [ "$LG_REPLICAS" != "0" ]; then
  k scale deploy loadgen --replicas=0 >/dev/null
  k wait --for=delete pod -l app.kubernetes.io/name=loadgen --timeout=90s >/dev/null 2>&1
  ok "loadgen paused (was replicas=$LG_REPLICAS)"
fi
resume_loadgen() {
  if [ -n "$LG_REPLICAS" ] && [ "$LG_REPLICAS" != "0" ]; then
    k scale deploy loadgen --replicas="$LG_REPLICAS" >/dev/null 2>&1 || true
  fi
}
trap resume_loadgen EXIT

# --- 2. quiesce and snapshot --------------------------------------------
wait_outbox_drained
sleep 10   # let consumers finish in-flight deliveries before the wipe
snapshot_counts /tmp/12-restart-pre.txt
PRE_TOTAL=$(awk -F= '{s+=$2} END {print s+0}' /tmp/12-restart-pre.txt)
ok "pre-restart snapshot: $(wc -l < /tmp/12-restart-pre.txt) tables, $PRE_TOTAL rows"

# --- 3. kill everything ---------------------------------------------------
k delete pods --all --wait=false >/dev/null
ok "deleted all pods in namespace"

DEPLOYS=$(k get deploy --no-headers -o custom-columns=:metadata.name)
for d in $DEPLOYS; do
  if k rollout status "deploy/$d" --timeout=600s >/dev/null 2>&1; then
    ok "rollout $d"
  else
    bad "rollout $d did not recover"
  fi
done

# --- 4. prove state survived ----------------------------------------------
PGPOD=$(pg_pod)
snapshot_counts /tmp/12-restart-post.txt
if diff -u /tmp/12-restart-pre.txt /tmp/12-restart-post.txt > /tmp/12-restart-diff.txt; then
  ok "all $(wc -l < /tmp/12-restart-pre.txt) snapshot tables identical across restart"
else
  bad "snapshot row counts changed across restart:"
  sed 's/^/    /' /tmp/12-restart-diff.txt
fi

# --- 5. full suite against the reborn cluster ------------------------------
SUITE_PASS=0; SUITE_FAIL=0; SUITE_BROKEN=""
for script in $SUITE; do
  echo "-- $script"
  out=$(bash "$script" 2>&1)
  line=$(echo "$out" | grep '== RESULT' | tail -1)
  if [ -n "$line" ]; then
    p=$(echo "$line" | sed 's/.*pass=\([0-9]*\).*/\1/')
    f=$(echo "$line" | sed 's/.*fail=\([0-9]*\).*/\1/')
    SUITE_PASS=$((SUITE_PASS + p)); SUITE_FAIL=$((SUITE_FAIL + f))
    echo "   pass=$p fail=$f"
    [ "$f" -gt 0 ] && echo "$out" | grep '✗' | sed 's/^/    /'
  else
    SUITE_FAIL=$((SUITE_FAIL + 1)); SUITE_BROKEN="$SUITE_BROKEN $script"
    echo "$out" | tail -5 | sed 's/^/    /'
    echo "   NO RESULT LINE (counted as 1 failure)"
  fi
done

# --- 6. verdict -------------------------------------------------------------
resume_loadgen
trap - EXIT
[ -n "$LG_REPLICAS" ] && [ "$LG_REPLICAS" != "0" ] && ok "loadgen resumed (replicas=$LG_REPLICAS)"

echo "== SUITE pass=$SUITE_PASS fail=$SUITE_FAIL${SUITE_BROKEN:+ broken:$SUITE_BROKEN}"
PASS=$((PASS + SUITE_PASS)); FAIL=$((FAIL + SUITE_FAIL))
summary
