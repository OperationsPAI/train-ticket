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

### Wave 12 — channels & tidy (DONE)

- Notification: SMTP adapter + in-cluster mailpit; TICKET_ISSUED delivers
  email to synthetic traveler mailboxes, asserted by e2e 08 (IN_APP remains
  default elsewhere).
- Repo tidy: future-scope skeleton READMEs, docs sweep, dead-code pass
  (removed the unwired DLQ writers that produced bare-envelope entries).
- `deploy/e2e/12-restart.sh` whole-cluster restart certification: first full
  run 191/0.

### DLQ audit wave (DONE)

Live-cluster DLQ attribution under continuous load drove a taxonomy hardening
pass across consumers (REQ-088..091 + payment/legacy-acl hotfixes): notification
trigger contract validation, capacity sold-out fast-fail via
`CapacityHoldFailed(NO_AVAILABLE_CAPACITY)`, booking-orchestration terminal-state
triage, journey-order catch-all classification, payment zero-refund no-op, and
kit-level fixes so no failure path is silently swallowed (java-kit, rust-kit).
Certification: all monitored DLQ streams frozen for 16 minutes under loadgen;
battery 73/0.

### Wave 13 — observability & follow-ups (DONE)

- REQ-092/093: second-order DLQ findings (late provider confirmation on
  capacity-failed bookings; finance-settlement refund-lag reconciliation path).
- REQ-094: silent-swallow / log-quality audit for python-kit, ts-kit, go-kit.
- REQ-095..098: OTel collector in the kind cluster plus OTLP traces from all
  23 services through the five language kits (env-driven, zero-overhead when
  unconfigured). Certified live: 23/23 services attributed by service.name in
  collector debug logs, all DLQ streams zero-growth for 16 minutes under
  loadgen, e2e battery 73/0.

## Historical planning material

Earlier revisions of this file tracked the pre-implementation WP-01 brief cycle
(rejections/blockers). That content is obsolete — the merged PR trail on
`refactor/greenfield-ddd` is the historical record.

### Wave 14 — distributed tracing & coverage tail (DONE)

- Contract ruling: optional traceparent/tracestate on the wire envelope
  (additive, observability-only). All five kits inject at envelope creation
  and parent consumer spans from it (REQ-099/100/101/101B). Certified live:
  single trace spanning 11 services; Go-bridged trace spanning 9.
- REQ-102: trip-planning lifecycle flake fix + 13-observability.sh smoke.
- REQ-103: repo-wide HTTP endpoint drift audit; the fulfillment completions
  endpoint was the only drift, now documented.
- REQ-105: provider-integration internal HTTP command endpoints removed by
  ruling (dead API; command surface is event-only).
- REQ-104: loadgen long-tail prober — real-ID read probes, payment-cancel /
  order-cancel / support lifecycle branches, low-frequency ops actor
  (reporting, finance reads, supplier-catalog).
