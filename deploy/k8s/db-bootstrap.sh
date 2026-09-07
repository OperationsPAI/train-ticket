#!/usr/bin/env bash
#
# Idempotent PostgreSQL database bootstrap.
#
# WHY THIS EXISTS
# ---------------
# The postgres image runs /docker-entrypoint-initdb.d/* ONLY when PGDATA is
# empty. This deployment mounts the `postgres-data` PVC, so on any cluster that
# has already initialised, a database added to an initdb script later is never
# created. `group_booking` was missing for an unknown length of time because of
# exactly that, and nothing noticed: the owning service's readiness check did
# not touch Postgres.
#
# This script runs as a Job on every deploy, against a *running* Postgres, and
# is safe to run any number of times.
#
# SINGLE SOURCE OF TRUTH
# ----------------------
# The database list is not written down anywhere. It is extracted at run time
# from deploy/k8s/services.yaml, which is mounted verbatim into the Job by the
# `db-bootstrap-manifests` ConfigMap (see deploy/k8s/kustomization.yaml). The
# manifest is the thing that actually configures each service's DATABASE_URL,
# so a database this script does not create is a database no service asked for,
# and vice versa. There is no second list to drift.
#
# Runs in postgres:16-alpine (bash, psql, pg_isready, busybox coreutils).

set -euo pipefail
export LC_ALL=C

MANIFEST="${MANIFEST:-/manifests/services.yaml}"
PGHOST="${PGHOST:-postgres}"
PGPORT="${PGPORT:-5432}"
WAIT_SECONDS="${WAIT_SECONDS:-300}"
export PGHOST PGPORT

WANTED=/tmp/db-bootstrap-wanted.txt
HAVE=/tmp/db-bootstrap-have.txt
CREATE_SQL=/tmp/db-bootstrap-create.sql

log() { printf '[db-bootstrap] %s\n' "$*"; }
die() { printf '[db-bootstrap] FATAL: %s\n' "$*" >&2; exit 1; }

[ -n "${PGUSER:-}" ] || die "PGUSER is not set"
[ -n "${PGPASSWORD:-}" ] || die "PGPASSWORD is not set"

# --------------------------------------------------------------------------
# 1. Ordering: do nothing until Postgres actually accepts connections.
#    On a genuinely fresh volume the image's own initdb phase listens only on
#    a unix socket, so a successful TCP pg_isready also means initdb finished.
# --------------------------------------------------------------------------
log "waiting up to ${WAIT_SECONDS}s for postgres at ${PGHOST}:${PGPORT}"
deadline=$((SECONDS + WAIT_SECONDS))
until pg_isready -q -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    die "postgres did not become ready within ${WAIT_SECONDS}s"
  fi
  sleep 2
done
log "postgres is accepting connections"

# --------------------------------------------------------------------------
# 2. Derive the database list from the deployment manifest.
#    Matching on '@postgres:<port>/<name>' anchors on the DSN authority, so
#    `image: postgres:16-alpine` and similar cannot be mistaken for a DSN.
#    The [A-Za-z0-9_] class also means a name can never carry SQL syntax, so
#    the interpolation in step 3 cannot be an injection vector.
# --------------------------------------------------------------------------
[ -r "$MANIFEST" ] || die "manifest ${MANIFEST} is missing or unreadable"
# `|| true`: grep exits 1 on no match, which under `set -e -o pipefail` would
# abort the script here with no diagnostic at all. The empty-list check below
# is the intended way to fail this case, because it says why.
grep -oE '@postgres:[0-9]+/[A-Za-z0-9_]+' "$MANIFEST" | cut -d/ -f2 | sort -u >"$WANTED" || true
want=$(wc -l <"$WANTED" | tr -d ' ')
if [ "$want" -eq 0 ]; then
  die "derived 0 databases from ${MANIFEST} -- refusing to report success on an empty list"
fi
log "derived ${want} databases from ${MANIFEST}:"
while IFS= read -r db; do log "    ${db}"; done <"$WANTED"

# --------------------------------------------------------------------------
# 3. Create the missing ones.
#    CREATE DATABASE cannot run inside a transaction and has no IF NOT EXISTS,
#    hence the SELECT-guard + \gexec form. \gexec needs psql's metacommand
#    parser, which `psql -c` does not apply to a mixed SQL+backslash string --
#    so this is written to a file and fed with -f.
#
#    ON_ERROR_STOP is deliberately NOT set. The SELECT guard is not atomic with
#    the CREATE it emits, so two bootstraps racing (two `make deploy`s, or a
#    Job retry overlapping its predecessor) can both pass the guard and the
#    loser gets SQLSTATE 42P04 / a unique violation on pg_database. Under
#    ON_ERROR_STOP=1 that aborts the whole file and every database after the
#    collision is skipped -- an interrupted bootstrap reporting failure while
#    leaving real gaps. Verified: three concurrent runs, one aborted at the
#    first collision with 1 of 38 still missing.
#
#    Instead each statement is allowed to fail independently and the pass is
#    repeated while it is still making progress. A lost race is self-correcting
#    (the winner created it); a real inability to create is not, so the loop
#    stops converging and step 4 reports it.
# --------------------------------------------------------------------------
: >"$CREATE_SQL"
while IFS= read -r db; do
  {
    printf "SELECT 'CREATE DATABASE \"%s\"'\n" "$db"
    printf "WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '%s')\n" "$db"
    printf '\\gexec\n'
  } >>"$CREATE_SQL"
done <"$WANTED"

for attempt in 1 2 3; do
  before=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -tAc \
    'SELECT count(*) FROM pg_database' 2>/dev/null || echo 0)
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -q -f "$CREATE_SQL" || true
  after=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -tAc \
    'SELECT count(*) FROM pg_database' 2>/dev/null || echo 0)
  # Nothing left to create, or this pass changed nothing and a further
  # identical pass would change nothing either.
  if ! psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -tAc 'SELECT datname FROM pg_database' 2>/dev/null \
      | sort | comm -23 "$WANTED" - | grep -q .; then
    break
  fi
  log "pass ${attempt}: ${before} -> ${after} databases, some still missing; retrying"
  sleep 2
done

# --------------------------------------------------------------------------
# 4. Verify, and be loud. This is what makes the Job meaningful: it is the
#    check that would have caught the missing group_booking database.
# --------------------------------------------------------------------------
if ! psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -tAc 'SELECT datname FROM pg_database' | sort >"$HAVE"; then
  # Reached when psql can reach the port but cannot run the query at all --
  # in practice a bad password or a revoked role. Named explicitly so it is
  # not confused with "connected fine, databases missing" below.
  die "could not list databases as role '${PGUSER}' (see the psql error above)"
fi
missing=$(comm -23 "$WANTED" "$HAVE" || true)
if [ -n "$missing" ]; then
  log "the following databases are still missing after bootstrap:"
  printf '[db-bootstrap]     - %s\n' $missing >&2
  die "$(printf '%s\n' "$missing" | wc -l | tr -d ' ') of ${want} databases could not be created"
fi

log "OK: all ${want} databases required by ${MANIFEST} exist"
