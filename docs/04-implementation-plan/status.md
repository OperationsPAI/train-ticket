# Implementation Plan Status

Last updated: 2026-07-08. Authoritative snapshot: `docs/00-current-status.md`.

## Phase 1 — DONE (certified 2026-07-07)

WP-01..WP-23 delivered via AgentM WorkGraph (REQ-003..REQ-071, all merged to
`refactor/greenfield-ddd`). Certification: the original 11 e2e scripts / 159
assertions green on the live kind cluster. Six future-scope domains are excluded
by roadmap ruling: waitlist, wallet-promotion, dispatch, ancillary-service,
transfer-management, and disruption-recovery.

## Phase 2 — engineering hardening

Architecture ruling: `docs/08-contracts/persistence.md`.

### Wave 10 — persistence foundation (DONE)

| Task | Scope |
|---|---|
| REQ-072 | PostgreSQL deployment (PVC, per-service DBs, secret), `DATABASE_URL` wiring for all services, Redis AOF |
| REQ-073 | java-kit `persistence` module + pilot migration: payment |
| REQ-074 | python-kit `storage.py` + pilot migration: fare-pricing |
| REQ-075 | ts-kit `storage.ts` + pilot migration: notification |
| REQ-076 | go-kit `storage/` + pilot migration: place-network |
| REQ-077 | rust-kit `storage.rs` + pilot migration: capacity-availability |

Each kit provides migration running, aggregate snapshot persistence with
optimistic concurrency, transactional outbox relay, durable `processed_events`
dedup, and DB-backed idempotency behind the existing ports.

### Wave 11 — persistence rollout (DONE)

All 23 deployed business services now use the persistence baseline: PostgreSQL
aggregate snapshot rows, transactional outbox publishing, durable consumer
dedup, and DB-backed idempotency where the service exposes mutating HTTP
endpoints. Redis Streams remain the event bus; Redis AOF preserves stream and
consumer-group state across Redis restarts.

`deploy/e2e/12-restart.sh` is the whole-cluster restart certification script.
It pauses `deploy/loadgen` when present, verifies aggregate snapshot row counts
across workload restarts, runs a smoke flow after recovery, and resumes loadgen.
The e2e suite now has 12 scripts and 159+ assertions.

### Wave 12 — channels & tidy (IN PROGRESS)

- Notification: SMTP adapter + in-cluster mailpit; channel selection per
  contract (IN_APP remains default).
- Repo tidy: future-scope skeleton READMEs, docs sweep, dead-code pass.

## Historical planning material

Earlier revisions of this file tracked the pre-implementation WP-01 brief cycle
(rejections/blockers). That content is obsolete — the merged PR trail on
`refactor/greenfield-ddd` is the historical record.
