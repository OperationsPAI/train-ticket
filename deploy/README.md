# Local kind integration environment

This directory contains the Docker and Kubernetes assets for running the 38
deployed Train Ticket business services (`deploy/k8s/services.yaml` defines 38
Deployments) plus Redis Streams and PostgreSQL in a local kind cluster for 联调.

## Prerequisites

- Docker
- kind, with a cluster already created
- kubectl with kustomize support (`kubectl kustomize`)
- python3 (used by the seed and smoke scripts)

## One command

```bash
make deploy
```

That is the whole thing, from a running kind cluster to a verified stack. It is
idempotent — running it again is safe, and is the intended way to apply a
change. In order, it:

1. **Builds and loads images** — `deploy/build-images.sh`, then `kind load` for
   each. The image list is derived from the `train-ticket/<name>:` references
   in `deploy/k8s/*.yaml`, so it cannot drift from what the cluster pulls.
2. **Applies manifests** — `kubectl apply -k deploy/k8s`.
3. **Waits for PostgreSQL and Redis** to be rolled out.
4. **Bootstraps databases** — the `db-bootstrap` Job (below). Blocks until it
   completes; on failure it prints the Job log and stops the deploy.
5. **Repairs services that lost the database race** —
   `deploy/repair-unready.sh` restarts only the deployments that are not ready,
   then waits for the namespace to converge.
6. **Seeds reference data** — `deploy/seed.sh` (below).
7. **Smoke-verifies** — `deploy/smoke.sh` (below). Non-zero exit on failure.

### Variables

| Variable | Default | Meaning |
| --- | --- | --- |
| `IMAGE_TAG` | `local` | Must match the tag in the manifests. |
| `KIND_CLUSTER` | `train-ticket` | Cluster to `kind load` into. |
| `KCTX` | `kind-arl-test` | kubectl context. The e2e scripts hardcode this. |
| `NAMESPACE` | `train-ticket` | Target namespace. |
| `ROLLOUT_TIMEOUT` | `300s` | Per-wait timeout. |

### Skipping the image build

```bash
make deploy-fast
```

Same pipeline without the 39-image rebuild. A manifest-only change — a probe, a
resource limit, the loadgen config — does not change any image, and forcing a
full rebuild for those is what pushed people back to running the steps by hand.
`make deploy` remains the safe default; Docker layer caching already makes an
unchanged rebuild fairly cheap.

### Individual steps

Each stage is also a target, if you need to run one in isolation:
`deploy-images`, `deploy-apply`, `deploy-wait`, `deploy-db-bootstrap`,
`deploy-seed`, `deploy-check`. They declare their predecessors, so running a
later one runs the earlier ones too.

## Database bootstrap

`deploy/k8s/db-bootstrap.yaml` defines a Job that runs on every apply and
guarantees that every database the manifests require exists.

**Why it exists.** The `postgres-initdb` ConfigMap is mounted at
`/docker-entrypoint-initdb.d`, which the postgres image executes **only when
PGDATA is empty**. This deployment mounts a PVC, so on any cluster that has
already initialised, a database added to that ConfigMap later is never created.
`group_booking` was missing for an unknown length of time for exactly this
reason, and nothing caught it because the owning service's readiness check did
not touch Postgres.

**How the list is derived.** `deploy/k8s/services.yaml` is mounted into the Job
verbatim through a generated ConfigMap, and `deploy/k8s/db-bootstrap.sh`
extracts the database names from the `@postgres:5432/<db>` DSNs in it. The
manifest that configures each service's `DATABASE_URL` is therefore the single
source of truth: a database the Job does not create is one no service asked
for. There is no second list to drift — a mismatch between the initdb list and
the manifests is the bug that caused the outage.

**Properties.** It waits for Postgres to accept connections before doing
anything; uses a `SELECT ... WHERE NOT EXISTS ... \gexec` guard so re-runs are
no-ops; tolerates losing a creation race to a concurrent run and re-converges;
and finishes by *verifying* every required database is present, failing loudly
and naming the missing ones if not. A failed bootstrap fails the deploy.

Inspect it with:

```bash
kubectl -n train-ticket logs job/db-bootstrap
```

Note the initdb ConfigMap in `postgres.yaml` is now belt-and-braces only: it
still seeds a genuinely fresh volume, but nothing depends on it being correct.

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
`deploy/smoke.sh` checks three independent things, all derived from the
manifest with no hardcoded names or counts:

1. **Every declared Service has a ready Endpoint.** An unready pod is removed
   from Endpoints, so this catches "Running but 503 on readiness", which pod
   phase is blind to.
2. **Every required database exists.** Asserted independently of the bootstrap
   Job, so it still catches a bootstrap that was skipped or ran against a stale
   manifest.
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

Tune it by editing `deploy/k8s/loadgen-config.yaml`; the hashed
`loadgen-config` ConfigMap is generated by `deploy/k8s/kustomization.yaml`, so
an apply rolls the Deployment automatically. `make deploy-fast` is the quickest
way to apply a config change. The restart certification script pauses this
deployment before comparing snapshot row counts and resumes it afterward.

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
