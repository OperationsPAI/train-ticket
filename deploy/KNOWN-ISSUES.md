# Known issues for local kind integration

The 38-service stack is expected to expose the contract probe endpoints the
Helm chart probes (`deploy/helm/train-ticket/templates/services.yaml`), including
`/healthz` and `/readyz`, and to start with the image entrypoints built by
`deploy/build-images.sh`. Keep this file limited to active operator-facing
issues; resolved smoke-run notes and build implementation details belong in git
history or the deployment README, not here.

## Active issues

### (RESOLVED) Postgres databases are not created on an existing cluster

The `postgres-<shard>-initdb` ConfigMap is only executed when PGDATA is empty,
so on a cluster with an existing PVC, databases added to it later were never
created and the owning service crash-looped on connect. This required a manual
`CREATE DATABASE` per service and is what hid the missing `group_booking`.

Now handled by the per-shard bootstrap Job the chart renders from
`deploy/helm/train-ticket/templates/db-bootstrap.yaml`. It is a chart hook
(`helm.sh/hook: pre-upgrade,post-install`), so every `helm upgrade --install` —
i.e. every `make deploy` — runs it against a live Postgres, creates any database
in `postgres.instances.<shard>.databases` that is missing, and fails the release
if it cannot. `deploy/verify-databases.sh` then re-checks the same thing from the
`@postgres-<shard>:5432/<db>` DSNs in the rendered release. No manual step
remains. See `deploy/README.md`, which also explains why the hook cannot be
`post-upgrade` (it would deadlock against `--wait`).

Diagnose with:

```sh
kubectl -n train-ticket logs job/postgres-core-bootstrap
```

Still true, and the reason the Job exists rather than a fixed initdb list:
editing `postgres-<shard>-initdb` alone has no effect on an initialised cluster.
That ConfigMap is now belt-and-braces for genuinely fresh volumes only.

### Redis is transport-only and has a hard memory ceiling

Redis is the event bus, not a system of record, and runs with no persistent
volume by design (`docs/00-current-status.md`). On the kind profile it is
configured with `maxmemory 3gb` inside a 4Gi container limit and
`maxmemory-policy noeviction` (`redis.maxmemory` /
`redis.resources.limits.memory` in `deploy/helm/train-ticket/values.yaml`). The ~75% ratio is the point: maxmemory
is a logical-dataset bound that does not account for allocator fragmentation or
client output buffers, so it must stay below the cgroup limit for Redis to
enforce its own ceiling rather than be OOMKilled. Change the two together.

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

### (RESOLVED) Duplicate trip-planning Dockerfile

`deploy/docker/trip-planning/Dockerfile` and
`deploy/docker/trip-planning-rs/Dockerfile` were byte-identical and both built
the Rust crate `services/trip-planning-rs`. Only the first was ever referenced:
the chart renders the image name `train-ticket/trip-planning:local` (from the
`services.trip-planning` key in `deploy/helm/train-ticket/values.yaml`), and
`deploy/build-images.sh` derives what it builds from the rendered release, so it
looks for `deploy/docker/trip-planning/Dockerfile`.

The `-rs` copy has been deleted. Note the confusing part that made this take two
attempts to read correctly: the *directory* name says `trip-planning` but the
Dockerfile inside it runs `cargo build` against `services/trip-planning-rs`.
The image name and the source language are unrelated here.

### Trip Planning does not enforce minimum connection times

`trip-planning` was rewritten from Python to Rust for performance, and the
Rust implementation (`services/trip-planning-rs`, the only one now — the Python
service was deleted) does **not** consume `MctRulePublished` / `MctRuleRetired`.
The retired Python implementation did: it kept an in-memory table of
`MinimumConnectionTimeRule` keyed by `(mctRuleId, version)` and used it when
assembling multi-leg candidates.

`transfer-management` still publishes those events — they are in
`docs/08-contracts/events/transfer-management.md` and
`services/transfer-management/src/transfer_management/application/service.py`,
and `offer-management` consumes them — so this is a real gap on the Rust side,
not a retired contract.

What the Rust version does instead: `domain.rs` scores itineraries with
`connection_count()` and honours a `self_transfer_allowed` flag, so multi-leg
results are produced and penalised for connection count, but nothing bounds a
connection by the published minimum. The practical effect is that Trip Planning
can return an itinerary whose connection is shorter than the operator's declared
MCT. Amounts and rankings are otherwise unaffected.

Closing this means adding an `MctRulePublished`/`MctRuleRetired` arm to
`PlanIndex::apply_event` in `services/trip-planning-rs/src/plan_index.rs` and a
feasibility check where candidates are assembled.

### (RESOLVED) `deploy/build-images.sh` image names must track the deployment

The script used to carry a hand-maintained `services=()` array that had to be
kept in sync with the manifests by hand. It drifted — four deployed services
(`group-booking`, `invoicing`, `loyalty-membership`, `travel-insurance`) were
missing from it — and the failure mode was a silent
`ErrImagePull`/`ImagePullBackOff` on an otherwise clean build+deploy.

The list is now derived at run time from the `train-ticket/<name>:` image
references in the **rendered** Helm release (`deploy/render-manifests.sh`), so it
cannot diverge from what the cluster pulls. Rendering rather than grepping the
chart sources is load-bearing: a template contains
`{{ include "train-ticket.image" ... }}`, not an image name, so a grep over the
templates would match nothing and silently derive an empty list. The script also
fails up front, before building anything, if the release references an image with
no `deploy/docker/<name>/Dockerfile`.
