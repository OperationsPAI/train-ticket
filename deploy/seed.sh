#!/usr/bin/env bash
#
# Deploy-time seeding: the minimum reference data a freshly deployed stack
# needs to be functional.
#
# WHY THIS IS SEPARATE FROM THE E2E SUITE
# ---------------------------------------
# deploy/e2e/ holds 23 scripts, and they are the TESTS. Running all of them on
# every deploy conflates two different jobs: "make the stack usable" and "prove
# the stack is correct". The suite takes minutes, asserts hundreds of things
# that can fail for reasons unrelated to deployment, and several scripts
# deliberately drive negative paths (frozen accounts, blocked risk cases,
# whole-cluster restarts in 12-restart.sh) that have no business running as
# part of bringing an environment up.
#
# So this seeds the subset whose *side effects* the stack needs, and leaves the
# rest to `make e2e`. Each script below is included for a specific reason:
#
#   01-seed.sh       Reference data: places, transport nodes, a scheduled
#                    service and segment, on a departure date 30 days out.
#                    Nothing can be searched, quoted, or booked without it,
#                    and it writes deploy/e2e/.refs.env, which later scripts
#                    and manual pokes both read.
#
#   07-fare-rules.sh Its final step republishes the DEFAULT
#                    supplier-default/contract-default rule set that every
#                    other script and the resident loadgen quote against, with
#                    a 2-year window. fare-pricing does install a fallback
#                    default at startup, but only when its store is empty --
#                    on an existing cluster it does not, so an expired window
#                    stays expired until this runs. The windows here were just
#                    made relative, so re-running this is exactly how a cluster
#                    gets non-expired pricing.
#
#   21-identity.sh   Publishes the identity-rail rule set and student
#                    eligibility certificates, also on newly-relative windows.
#                    Purchases route through real-name verification, so stale
#                    windows here block the purchase path.
#
# 02-purchase.sh is deliberately NOT seeded: it creates a live order, payment
# and entitlement, which is test traffic, not reference data. The resident
# loadgen produces that continuously anyway.
#
# Idempotency: these scripts create fresh entities per run (new place codes,
# version-stamped rule sets) rather than mutating fixed ones, so re-running
# adds a little data but never conflicts. Re-running is in fact the supported
# way to refresh validity windows.

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
E2E_DIR="${ROOT_DIR}/deploy/e2e"
NS="${NAMESPACE:-train-ticket}"
KCTX="${KCTX:-$(kubectl config current-context 2>/dev/null || echo '')}"

k() {
  if [ -n "$KCTX" ]; then
    kubectl --context "$KCTX" -n "$NS" "$@"
  else
    kubectl -n "$NS" "$@"
  fi
}

# ── Pause the resident loadgen for the duration of seeding.
#
# WHY: 21-identity.sh asserts that six low-frequency events (PurchaseLimitFact*,
# EligibilityUsage*) appear in events:identity-verification by scanning the last
# 120 entries with XREVRANGE. The loadgen drives real-name verification
# continuously, so it publishes VerificationCaseStarted / VerificationPassed /
# CredentialRegistered into that same stream fast enough to push the e2e events
# out of that window within seconds -- the stream sits pinned at its trim cap.
# The assertion therefore passed or failed depending on how warmed-up the
# loadgen happened to be: it passed when seeding ran just after a loadgen
# restart and failed on a quiet re-deploy, which made `make deploy` flaky for a
# reason that had nothing to do with the deployment.
#
# deploy/e2e/12-restart.sh already establishes this pattern; this reuses it. The
# trap restores the original replica count on any exit path, including failure,
# so a failed seed never leaves the cluster without load.
LG_REPLICAS="$(k get deploy loadgen -o jsonpath='{.spec.replicas}' 2>/dev/null || echo '')"
resume_loadgen() {
  if [ -n "$LG_REPLICAS" ] && [ "$LG_REPLICAS" != "0" ]; then
    k scale deploy loadgen --replicas="$LG_REPLICAS" >/dev/null 2>&1 \
      && echo "seed: loadgen resumed (replicas=${LG_REPLICAS})" \
      || echo "seed: WARNING could not resume loadgen -- scale it back manually" >&2
  fi
}
trap resume_loadgen EXIT
if [ -n "$LG_REPLICAS" ] && [ "$LG_REPLICAS" != "0" ]; then
  k scale deploy loadgen --replicas=0 >/dev/null 2>&1 || true
  k wait --for=delete pod -l app.kubernetes.io/name=loadgen --timeout=90s >/dev/null 2>&1 || true
  echo "seed: loadgen paused (was replicas=${LG_REPLICAS})"
fi

# Keep this list ordered: 01 must run first, because 07 sources the .refs.env
# it writes (P_BJ / P_SH / SERVICE_DATE).
SEED_SCRIPTS=(
  01-seed.sh
  07-fare-rules.sh
  21-identity.sh
)

failed=()
for script in "${SEED_SCRIPTS[@]}"; do
  path="${E2E_DIR}/${script}"
  if [ ! -x "$path" ]; then
    echo "seed: ${path} is missing or not executable" >&2
    failed+=("$script")
    continue
  fi
  echo
  echo "======================================================================"
  echo "== seed: ${script}"
  echo "======================================================================"
  # These scripts end in `summary`, which exits non-zero if any assertion
  # failed. A seed script that could not do its job must not be silent.
  if "$path"; then
    echo "-- ${script}: OK"
  else
    echo "-- ${script}: FAILED" >&2
    failed+=("$script")
  fi
done

echo
if [ "${#failed[@]}" -eq 0 ]; then
  echo "SEED OK: ${#SEED_SCRIPTS[@]} scripts completed."
  exit 0
fi
echo "SEED FAILED: ${failed[*]}" >&2
exit 1
