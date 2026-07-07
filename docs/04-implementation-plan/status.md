# Implementation Plan Status

Last updated: 2026-07-07. Authoritative snapshot: `docs/00-current-status.md`.

## Phase 1 — DONE (certified 2026-07-07)

WP-01..WP-23 delivered via AgentM WorkGraph (REQ-003..REQ-071, all merged
to `refactor/greenfield-ddd`). Certification: 11 e2e scripts / 159
asserts green on the live kind cluster, 24/24 pods healthy. Six
future-scope domains excluded by roadmap ruling: waitlist,
wallet-promotion, dispatch, ancillary, transfer, disruption-recovery.

## Phase 2 — engineering hardening (IN PROGRESS)

Architecture ruling: `docs/08-contracts/persistence.md`.

### Wave 10 — persistence foundation
| Task | Scope |
|---|---|
| REQ-072 | PostgreSQL deployment (PVC, per-service DBs, secrets), DATABASE_URL wiring for all services, redis AOF |
| REQ-073 | java-kit `persistence` module + pilot migration: payment |
| REQ-074 | python-kit `storage.py` + pilot migration: fare-pricing (also retires the rollback+503 ruling section) |
| REQ-075 | ts-kit `storage.ts` + pilot migration: notification |
| REQ-076 | go-kit `storage/` + pilot migration: place-network |
| REQ-077 | rust-kit `storage.rs` + pilot migration: capacity-availability |

REQ-073..077 depend on REQ-072. Live gate per pilot: full e2e regression
plus a restart-survival check (`kubectl delete pod` mid-flow, state
intact afterwards).

### Wave 11 — persistence rollout
Remaining stateful services migrate in per-language batches using the
proven kit modules (17 services; legacy-acl is stateless and exempt).
A new `deploy/e2e/12-restart.sh` certifies cross-service restart
survival once the rollout completes.

### Wave 12 — channels & tidy
- Notification: SMTP adapter + in-cluster mailpit; channel selection per
  contract (IN_APP remains default).
- Repo tidy: future-scope skeleton READMEs, docs sweep, dead-code pass.

## Historical planning material

Earlier revisions of this file tracked the pre-implementation WP-01
brief cycle (rejections/blockers). That content is obsolete — the merged
PR trail on `refactor/greenfield-ddd` is the historical record.
