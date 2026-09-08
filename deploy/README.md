# Local kind integration environment

This directory contains the Docker and Kubernetes assets for running the 38
deployed Train Ticket business services (`services` in
`deploy/helm/train-ticket/values.yaml` has 38 entries, one Deployment each) plus
Redis Streams and PostgreSQL in a local kind cluster for 联调.

Helm is the only deployment path. The kustomize overlay that used to live under
`deploy/` has been retired and deleted; the chart is
`deploy/helm/train-ticket`, and `deploy/helm/values-kind.yaml` carries the
local-cluster values.

## Prerequisites

- Docker
- kind, with a cluster already created
- kubectl
- helm 3
- python3 (used by the seed and smoke scripts)

## One command

```bash
make deploy
```

That is the whole thing, from a running kind cluster to a verified stack. It is
idempotent — running it again is safe, and is the intended way to apply a
change. In order, it:

1. **Builds and loads images** — `deploy/build-images.sh`, then `kind load` for
   each. The image list is derived at run time from the `train-ticket/<name>:`
   references in the *rendered* release (`deploy/render-manifests.sh`), so it
   cannot drift from what the cluster pulls.
2. **Installs or upgrades the release** — `helm upgrade --install train-ticket
   deploy/helm/train-ticket -f deploy/helm/values-kind.yaml --namespace
   train-ticket --create-namespace --wait`. One command replaces what used to be
   a `kubectl apply -k` of the kustomize overlay followed by three separate
   wait/bootstrap stages: helm applies, rolls the Deployments whose spec or
   config hash changed, runs the database-bootstrap hook, and `--wait` blocks
   until every Deployment is Available.
3. **Verifies databases** — `deploy/verify-databases.sh`, per shard. The chart
   hook already created them (step 2); this asserts it independently, because a
   missing database is silent until some service's first query.
4. **Rolls business services onto the freshly built images** — the rendered spec
   pins `:local`, so after `kind load` swaps the image behind that tag helm sees
   an unchanged release and the running pods would keep the old image forever.
5. **Repairs services that lost the database race** —
   `deploy/repair-unready.sh` restarts only the deployments that are not ready,
   then waits for the namespace to converge.
6. **Seeds reference data** — `deploy/seed.sh` (below).
7. **Smoke-verifies** — `deploy/smoke.sh` (below). Non-zero exit on failure.

The equivalent by hand, if you want only the install step:

```bash
helm upgrade --install train-ticket deploy/helm/train-ticket \
  -f deploy/helm/values-kind.yaml \
  --namespace train-ticket --create-namespace --wait
```

### Variables

| Variable | Default | Meaning |
| --- | --- | --- |
| `IMAGE_TAG` | `local` | Must match `global.imageTag` in the values file. |
| `KIND_CLUSTER` | `train-ticket` | Cluster to `kind load` into. |
| `KCTX` | current kubectl context | kubectl/helm context. |
| `NAMESPACE` | `train-ticket` | Target namespace (the release namespace). |
| `HELM_RELEASE` | `train-ticket` | Release name. |
| `HELM_CHART` | `deploy/helm/train-ticket` | Chart directory. |
| `HELM_VALUES` | `deploy/helm/values-kind.yaml` | Values file. Point at `values-prod.yaml` for a real cluster. |
| `HELM_TIMEOUT` | `15m` | `helm --timeout`. |
| `ROLLOUT_TIMEOUT` | `300s` | Per-wait timeout for the kubectl rollout steps. |

### Skipping the image build

```bash
make deploy-fast
```

Same pipeline without the 39-image rebuild. A chart-only change — a probe, a
resource limit, the loadgen config — does not change any image, and forcing a
full rebuild for those is what pushed people back to running the steps by hand.
`make deploy` remains the safe default; Docker layer caching already makes an
unchanged rebuild fairly cheap.

### Individual steps

Each stage is also a target, if you need to run one in isolation:
`deploy-images`, `deploy-apply`, `deploy-db-bootstrap`, `deploy-roll`,
`deploy-services`, `deploy-seed`, `deploy-check`. They declare their
predecessors, so running a later one runs the earlier ones too.

## Database bootstrap

`deploy/helm/train-ticket/templates/db-bootstrap.yaml` renders one Job per
Postgres shard — `postgres-core-bootstrap` on the default profile — and
guarantees that every database the release requires exists.

**Why it exists.** The `postgres-<shard>-initdb` ConfigMap is mounted at
`/docker-entrypoint-initdb.d`, which the postgres image executes **only when
PGDATA is empty**. The chart mounts a PVC, so on any cluster that has already
initialised, a database added to `postgres.instances.<shard>.databases` later is
never created. `group_booking` was missing for an unknown length of time for
exactly this reason, and nothing caught it because the owning service's
readiness check did not touch Postgres.

**Why it is a chart hook, and why `pre-upgrade,post-install` specifically.** The
Job is annotated `helm.sh/hook: pre-upgrade,post-install`, so helm runs it as
part of the release rather than leaving it to a separate `make` stage that could
be skipped. The hook phases are not interchangeable, and `post-upgrade` in
particular deadlocks:

Helm's order on an upgrade is resources → `--wait` blocks until everything is
Available → `post-upgrade` hooks. But the upgrade that *adds* a database is the
same upgrade that rolls the service needing it. That pod cannot become Available
without the database, so `--wait` blocks for the entire `--timeout`, and the
hook that would have created the database never runs at all. `pre-upgrade` has
none of that problem: Postgres is already running from the previous release, so
the databases are created before anything rolls onto them.

`post-install` covers the other direction. On a fresh install there is no server
to connect to before the resources exist, so a pre-install hook has nothing to
talk to — and an empty PGDATA means initdb has already created everything
anyway. The `post-install` hook is a no-op safety net for the one case that
falls between the two: a volume that survived a `helm uninstall`.

The Job also carries `helm.sh/hook-delete-policy: before-hook-creation`, which
deletes the previous Job first. Without it, a changed database list would hit
`Job spec is immutable`.

**How the list is derived.** The Job's `CREATE DATABASE` statements are
templated from `postgres.instances.<shard>.databases`, the same values the
services' `DATABASE_URL` DSNs are rendered from. There is no second list to
drift — a mismatch between the initdb list and the manifests is the bug that
caused the outage. `deploy/verify-databases.sh` closes the remaining gap from
the other end: it reads the `@postgres-<shard>:5432/<db>` DSNs out of the
*rendered* release and checks each shard, so a templating bug that ships a DSN
pointing at a database no shard's list contains is caught too — something the
hook structurally cannot catch, since it is generated from those same values.

**Properties.** It waits for Postgres to accept connections before doing
anything; guards each `CREATE DATABASE` with a `SELECT 1 FROM pg_database`
existence check so re-runs are no-ops; and is bounded by
`activeDeadlineSeconds: 300` so a shard that never comes back fails the release
instead of hanging `--wait`. A failed hook fails the `helm upgrade`, which fails
the deploy.

Inspect it with:

```bash
kubectl -n train-ticket logs job/postgres-core-bootstrap
```

Note the initdb ConfigMap in `templates/postgres.yaml` is now belt-and-braces
only: it still seeds a genuinely fresh volume, but nothing depends on it being
correct.

## Seeding

`make deploy` runs `deploy/seed.sh`, which runs three of the e2e scripts for
their side effects:

- `01-seed.sh` — places, transport nodes, a scheduled service and segment on a
  departure date 30 days out. Nothing can be searched, quoted or booked
  without it, and it writes `deploy/e2e/.refs.env`.
- `07-fare-rules.sh` — its final step republishes the default
  `supplier-default/contract-default` rule set that every other script and the
  resident loadgen quote against, with a 2-year window.
- `21-identity.sh` — publishes the `identity-rail` rule set and student
  eligibility certificates.

The validity windows in the last two were hardcoded and have just been made
relative, so **re-running the deploy is how a cluster gets non-expired
windows**. `fare-pricing` installs a fallback default at startup, but only when
its store is empty, so on an existing cluster an expired window stays expired
until seeding runs again.

Seeding is deliberately *not* the full suite. The 23 scripts in `deploy/e2e/`
are the **tests**: they take minutes, assert hundreds of things that can fail
for reasons unrelated to deployment, and several drive negative paths (frozen
accounts, blocked risk cases, and a whole-cluster restart in `12-restart.sh`)
that have no business running as part of bringing an environment up.
`02-purchase.sh` is excluded for the same reason — it creates a live order,
payment and entitlement, which is test traffic, not reference data.

## Smoke verification

```bash
make smoke        # against an already-deployed stack
```

`kubectl rollout status` is not sufficient: four services were recently
`Running` and passing liveness while permanently 503 on readiness, and
group-booking reported healthy while its database did not exist. So
`deploy/smoke.sh` checks three independent things, all derived from the rendered
release with no hardcoded names or counts:

1. **Every declared Service has a ready Endpoint.** An unready pod is removed
   from Endpoints, so this catches "Running but 503 on readiness", which pod
   phase is blind to. The bare `postgres` Service is skipped: it is an
   `ExternalName` alias to the default shard, so it has no selector and no
   Endpoints object by design.
2. **Every required database exists.** Asserted independently of the bootstrap
   hook, so it still catches a hook that was skipped or a DSN pointing at a
   database no shard's list contains.
3. **A real end-to-end write.** It POSTs a place to place-network, reads it
   back over HTTP, and then confirms the row is actually in
   `place_network.place_snapshots` in Postgres. The last assertion is the point:
   a service that returns 200 while failing to persist still fails here.

Exit code is non-zero on any failure, and each failure names the service or
database and prints the command to investigate it.

## E2E suite

`deploy/e2e/` contains 23 rerunnable scripts. Run the whole suite against a
deployed stack with:

```bash
make e2e
```

Or individually, from the repository root:

```bash
deploy/e2e/02-purchase.sh
deploy/e2e/12-restart.sh
```

Scripts after `01-seed.sh` read `deploy/e2e/.refs.env`, so run `01-seed.sh`
(or `make deploy`) first on a fresh cluster.

## Resident load generator

`deploy/loadgen-go/` contains the resident load generator (Go). Its image is
built by `deploy/build-images.sh` like every other service, from
`deploy/docker/loadgen/Dockerfile`.

Tune it by editing `deploy/helm/train-ticket/loadgen-config.yaml`. The chart
renders that file into the `loadgen-config` ConfigMap and also hashes it into the
pod's `checksum/config` annotation, so a `helm upgrade` after an edit changes the
pod template and rolls the Deployment automatically — the same
rollout-on-config-change kustomize used to get from its ConfigMap name hash.
`make deploy-fast` is the quickest way to apply a config change. The restart
certification script pauses this deployment before comparing snapshot row counts
and resumes it afterward.

Note that `deploy/loadgen-go/config.yaml` is a *local development sample*, not
what runs in the cluster; the two diverge deliberately (the deployed profile runs
with zero think time).

## What is still manual

- **Creating the kind cluster.** `make deploy` requires one to already exist;
  it will not create or delete clusters.
- **Pulling third-party images.** `postgres:16-alpine`, `redis:7-alpine`,
  `jaegertracing/all-in-one`, `otel/opentelemetry-collector-contrib`,
  `axllent/mailpit` and `curlimages/curl` are pulled by the node on demand. On
  an air-gapped or rate-limited machine, preload them:

  ```bash
  docker pull postgres:16-alpine
  kind load docker-image postgres:16-alpine --name train-ticket
  ```

- **Schema migrations.** Each service runs its own migrations at startup. The
  bootstrap Job creates *databases*, not tables.
- **Dropping databases.** The bootstrap only ever creates. Removing a service
  leaves its database behind, to be dropped deliberately.
- **Running the full e2e suite** (`make e2e`) — not part of deploy, by design.
