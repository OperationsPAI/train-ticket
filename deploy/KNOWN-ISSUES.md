# Known issues for local kind integration

The 38-service stack is expected to expose the contract probe endpoints used by
`deploy/k8s`, including `/healthz` and `/readyz`, and to start with the image
entrypoints built by `deploy/build-images.sh`. Keep this file limited to active
operator-facing issues; resolved smoke-run notes and build implementation
details belong in git history or the deployment README, not here.

## Active issues

### (RESOLVED) Postgres databases are not created on an existing cluster

The `postgres-initdb` ConfigMap is only executed when PGDATA is empty, so on a
cluster with an existing PVC, databases added to it later were never created
and the owning service crash-looped on connect. This required a manual
`CREATE DATABASE` per service and is what hid the missing `group_booking`.

Now handled by the `db-bootstrap` Job (`deploy/k8s/db-bootstrap.yaml`), which
runs on every `kubectl apply -k deploy/k8s` against a running Postgres,
derives the required databases from the `@postgres:5432/<db>` DSNs in
`deploy/k8s/services.yaml`, creates any that are missing, and fails loudly if
it cannot. No manual step remains. See `deploy/README.md`.

Diagnose with:

```sh
kubectl -n train-ticket logs job/db-bootstrap
```

Still true, and the reason the Job exists rather than a fixed initdb list:
editing `postgres-initdb` alone has no effect on an initialised cluster. That
ConfigMap is now belt-and-braces for genuinely fresh volumes only.

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

### (RESOLVED) `deploy/build-images.sh` image names must track the manifest

The script used to carry a hand-maintained `services=()` array that had to be
kept in sync with the manifests by hand. It drifted — four deployed services
(`group-booking`, `invoicing`, `loyalty-membership`, `travel-insurance`) were
missing from it — and the failure mode was a silent
`ErrImagePull`/`ImagePullBackOff` on an otherwise clean build+deploy.

The list is now derived at run time from the `train-ticket/<name>:` image
references in `deploy/k8s/*.yaml`, so it cannot diverge from what the cluster
pulls. The script also fails up front, before building anything, if a manifest
references an image with no `deploy/docker/<name>/Dockerfile`.
