# Known issues for local kind integration

The 38-service stack is expected to expose the contract probe endpoints used by
`deploy/k8s`, including `/healthz` and `/readyz`, and to start with the image
entrypoints built by `deploy/build-images.sh`. Keep this file limited to active
operator-facing issues; resolved smoke-run notes and build implementation
details belong in git history or the deployment README, not here.

## Active issues

### Postgres databases are not created on an existing cluster

The `postgres-initdb` ConfigMap in `deploy/k8s/postgres.yaml` is mounted at
`/docker-entrypoint-initdb.d`, which the postgres image runs **only when PGDATA
is empty**. This deployment mounts the `postgres-data` PVC, so on any cluster
where postgres has already initialised, adding a database to that ConfigMap has
no effect and the owning service will crash-loop on connect.

Operator action after adding a service (or when adopting a cluster created
before the five databases `corporate_travel`, `group_booking`,
`loyalty_membership`, `marketing_campaign`, `travel_insurance` were added):

```sh
kubectl exec -n train-ticket deploy/postgres -- \
  psql -U trainticket -d postgres -c 'CREATE DATABASE <db_name>'
```

Verify with:

```sh
kubectl exec -n train-ticket deploy/postgres -- \
  psql -U trainticket -d postgres -tAc 'select datname from pg_database order by 1'
```

### Redis is transport-only and has a hard memory ceiling

Redis is the event bus, not a system of record, and runs with no persistent
volume by design (`docs/00-current-status.md`). It is configured with
`maxmemory 768mb` inside a 1Gi container limit and `maxmemory-policy
noeviction`.

Consequences an operator must know:

- Under `noeviction`, once the dataset reaches `maxmemory` Redis **refuses
  writes** (`OOM command not allowed`) rather than dropping events. Publishers
  will error; consumers keep draining. This is deliberate — silently evicting
  undelivered events is worse than a visible write failure.
- If publishers stop trimming their streams (`XADD ... MAXLEN ~`), stream growth
  will eventually hit that ceiling. Check with
  `kubectl exec -n train-ticket deploy/redis -- redis-cli info memory` and
  `redis-cli --bigkeys`.
- Deleting the Redis pod wipes streams and consumer groups. That is expected;
  services recover from PostgreSQL via the transactional outbox and consumer
  groups are recreated on demand (`deploy/e2e/12-restart.sh` certifies this).

Historical note only, for recognising a recurrence: before the ceiling existed,
Redis grew unbounded and was OOMKilled by the kernel (exit code 137), taking the
whole stack down. Symptom to look for is `Last State: Terminated / Reason:
OOMKilled` in `kubectl describe pod -n train-ticket -l
app.kubernetes.io/name=redis`.

### Duplicate trip-planning Dockerfile

`deploy/docker/trip-planning/Dockerfile` and
`deploy/docker/trip-planning-rs/Dockerfile` are byte-identical and both build
the Rust crate `services/trip-planning-rs`. Only `trip-planning-rs` is correct:
`deploy/k8s/services.yaml` requires `train-ticket/trip-planning-rs:local`, and
that is what `deploy/build-images.sh` now builds.

The stale `deploy/docker/trip-planning/Dockerfile` still exists and is still
referenced by `skaffold.yaml` (`opspai/trip-planning`). Building via that path
produces an image name no manifest consumes. Prefer `deploy/build-images.sh`
until the duplicate is deleted and skaffold is repointed.

### `deploy/build-images.sh` image names must track the manifest

The script's `services=()` array doubles as the image-name list: it builds
`train-ticket/<entry>:local`. When adding or renaming a service, the entry must
match the image name in `deploy/k8s/services.yaml`, not the source directory
name — these have diverged before (see trip-planning above), and the failure
mode is a silent `ErrImagePull`/`ImagePullBackOff` on a clean build+deploy.

Cross-check the two sets after any change:

```sh
grep -oP 'train-ticket/\K[a-z-]+(?=:local)' deploy/k8s/services.yaml | sort -u
```

compared against the `services=()` entries; they must be identical (38 each).
