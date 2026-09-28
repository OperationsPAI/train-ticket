# Persistence & Durable Outbox — RULING (2026-07-07)

Last updated: 2026-09-28.

Status: ACCEPTED. Applies to every deployed service. This is the phase-2
storage baseline; PRs that touch state handling are checked against this
document field by field.

Supersedes: the "publish failure → rollback + 503" reliability ruling in
`api/fare-pricing.md` §Reliability. Once a service adopts the transactional
outbox below, command handlers commit state + outbox atomically and return
success; there is no publish-failure HTTP path anymore. The fare-pricing
migration task updates that contract section.

Unchanged rulings that this document builds on:
- Deterministic event ids (derive from consumed eventId / sourceRef).
- Consumer-side eventId dedup (now durable, see §4).
- Redis streams remain the event bus. Redis is NEVER a system of record.

## 1. Infrastructure

- PostgreSQL is split by workload shape into six instances — `core`, `hot`,
  `txn`, `read`, `user` and `event` — one Deployment per shard named
  `postgres-<shard>`, image `postgres:16-alpine`, strategy `Recreate`, single
  replica each, namespace `train-ticket`. The bare `postgres` Service is an
  `ExternalName` alias to `postgres.instances.defaultInstance` (`core`) with no
  pods behind it. Storage is a `local-path` PVC per shard, sized from
  `postgres.instances.<shard>.storageSize` (default 20Gi); the copies and
  cutover checks are recorded in the chart.
- Secrets `postgres-<shard>-credentials`: `POSTGRES_USER=trainticket`,
  `POSTGRES_PASSWORD=trainticket-dev` (dev cluster only; never reuse
  elsewhere).
- One database per service. Database name = service directory name with
  `-` → `_` (e.g. `services/fare-pricing` → `fare_pricing`). Each database is
  named in `postgres.instances.<shard>.databases`; all are created by the
  per-shard initdb ConfigMap script mounted at
  `/docker-entrypoint-initdb.d/`. A missing entry means the service starts,
  connects, and fails on its first query.
- Every service Deployment gets:
  `DATABASE_URL=postgresql://trainticket:trainticket-dev@postgres-<shard>:5432/<db>`
  where `<shard>` is the service's `pgInstance`, defaulting to `core`.
- Java services receive the same `DATABASE_URL` (postgres URI form); the
  java-kit adapter converts it to JDBC internally. There is exactly one
  canonical env var; do not introduce `JDBC_URL`, `PGHOST`, etc.
- Redis is the transport-only event bus and runs with **no persistent volume and
  no AOF**; with nothing mounted, an AOF would only write to the container's
  ephemeral filesystem. Consumer-group offsets and unconsumed stream entries do
  not survive a Redis restart. Recovery is from PostgreSQL through the
  transactional outbox and the durable replay archives.

## 2. Storage model

- Aggregate state is persisted as snapshot rows, one table per aggregate
  type, named `<aggregate>_snapshots`:

  ```sql
  CREATE TABLE <aggregate>_snapshots (
    id         text PRIMARY KEY,
    version    bigint NOT NULL,
    data       jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
  );
  ```

- Optimistic concurrency is mandatory:
  `UPDATE ... SET version = version + 1, data = $2, updated_at = now()
   WHERE id = $1 AND version = $3` — zero rows updated means a concurrent
  writer won; the handler retries the whole command or returns 409.
- Read models / list endpoints may add dedicated tables or generated
  columns; JSONB expression indexes are allowed. Full table scans on hot
  list endpoints are not — "full table scan" here means fetching all rows
  into the application and filtering/paginating in memory. Pushing
  filters/pagination into SQL is the requirement; an index-supported
  `SELECT count(*)` (or filtered count) to serve a contract-mandated
  `total` field is acceptable.
- Schema migrations: per-service ordered SQL files in
  `services/<svc>/migrations/NNN_description.sql`, applied at startup by
  the language kit's migration runner, recorded in
  `schema_migrations(version text PRIMARY KEY, applied_at timestamptz)`.
  Readiness stays false until migrations are applied.
- Domain layers stay persistence-ignorant: repository ports keep their
  current interfaces; the Postgres implementation lives in the service's
  infrastructure/adapter layer and uses only the kit's storage helpers.

## 3. Transactional outbox (event publishing)

- Every service that emits events gets one `outbox` table:

  ```sql
  CREATE TABLE outbox (
    seq          bigserial PRIMARY KEY,
    event_id     text NOT NULL UNIQUE,
    stream       text NOT NULL,
    envelope     jsonb NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
  );
  ```

  `envelope` is the exact JSON that will be XADDed (same shape that goes
  into the stream field `d` today — no re-serialization drift).
- Producers write the state change AND the outbox rows in ONE database
  transaction. HTTP success means the transaction committed. The old
  "rollback + 503 on publish failure" behavior is retired.
- Relay: a kit-provided background loop per service instance polls
  `SELECT ... WHERE published_at IS NULL ORDER BY seq LIMIT 100`,
  XADDs each envelope to its stream, then sets `published_at`. Poll
  interval ≤ 250 ms (e2e retry windows assume events land within a few
  seconds). Ordering per service is preserved by `seq`.
- Delivery is at-least-once (relay may crash between XADD and marking
  published). That is safe because consumer dedup is durable (§4) and
  event ids are deterministic.
- Published outbox rows are intentionally short-lived and are not historical
  replay evidence. Post Sales, Payment and Wallet / Promotion additionally
  archive the compensation-related event types listed in
  [durable event recovery](durable-event-recovery.md). Archive insertion shares
  the state/outbox transaction, uses a monotonic cursor and has an explicit
  1–90 day retention policy (7 days by default).

## 4. Durable consumer dedup & idempotency

- `processed_events(event_id text PRIMARY KEY, stream text,
  processed_at timestamptz NOT NULL DEFAULT now())` — the INSERT happens
  in the SAME transaction as the handler's state mutation;
  `ON CONFLICT DO NOTHING` + zero rows means duplicate → skip side
  effects, ack the message.
- HTTP idempotency records move from memory to
  `idempotency_records(key text PRIMARY KEY, request_hash text NOT NULL,
  status_code int NOT NULL, response_body jsonb, created_at timestamptz
  NOT NULL DEFAULT now())`. Middleware semantics are unchanged: same key
  + same hash replays the stored response; same key + different hash →
  `IDEMPOTENCY_KEY_REUSED`.
- Notification and Loyalty Membership persist poison-message evidence before
  Redis DLQ publication and XACK. Their replay checkpoints advance only after
  the normal handler transaction succeeds; a retry after a crash is absorbed
  by `processed_events`.

## 5. Kit obligations (one module per language, same port names)

| Kit | Module | Driver |
|---|---|---|
| java-kit | `persistence` | plain JDBC (no JPA/Hibernate/Flyway) |
| python-kit | `storage.py` | psycopg 3 |
| ts-kit | `storage.ts` | `pg` |
| go-kit | `storage/` | `pgx` |
| rust-kit | `storage.rs` | `sqlx` (runtime-checked queries) |

Each kit provides: DATABASE_URL parsing + pool, migration runner,
snapshot repository helper (get/save with optimistic version), outbox
appender + relay loop, processed-events guard, DB-backed idempotency
store — and KEEPS the existing in-memory implementations behind the same
ports for unit tests. `make check` must not require a running Postgres.

## 6. Failure semantics

- A shard's DB unreachable → its services' readiness fails → their traffic
  stops (single replica + Recreate per shard: a brief dev-cluster outage of that
  shard is accepted).
- Relay restart resumes from unpublished rows; duplicates are absorbed by
  §4.
- Pod restart must lose NOTHING: the live-gate check for every migrated
  service is `kubectl delete pod` mid-flow, then the flow completes and
  prior state is still queryable.
