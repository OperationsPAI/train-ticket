# Current Project Status

Last updated: 2026-07-08

## Status: Phase 1 complete; Phase 2 hardening complete (persistence, channels, DLQ audit)

The greenfield DDD rewrite is a working, end-to-end-verified system. All
Phase-1 work packages (WP-01..WP-23 of the accepted roadmap) are merged to
`refactor/greenfield-ddd`, deployed in the local kind integration environment,
and certified by the e2e suite.

Wave 11 completed the persistence baseline from
`docs/08-contracts/persistence.md`: all 23 deployed business services use
PostgreSQL aggregate snapshot rows with optimistic concurrency, transactional
outbox publishing, and durable `processed_events` consumer dedup/idempotency.
Redis Streams remain the event bus; Redis is transport only, not a system of
record. It runs without a persistent volume — a Redis pod deletion wipes
streams and consumer groups by design, and services recover from PostgreSQL
(the transactional outbox re-publishes anything unpublished; consumer groups
are recreated on demand). `deploy/e2e/12-restart.sh` certifies exactly this.

## What runs today

23 deployed business services + Redis Streams + PostgreSQL, all under
`deploy/k8s/`, with service images built by `deploy/build-images.sh`:

| Language | Services |
|---|---|
| Java (Boot 4) | admin-audit, booking-orchestration, finance-settlement, journey-order, payment, post-sales, traveler-profile |
| Python (FastAPI) | fare-pricing, legacy-acl, reporting, risk-compliance, trip-planning |
| Node (TS) | account, customer-service, notification, offer-management |
| Go | fulfillment, place-network, provider-integration, service-plan, supplier-catalog |
| Rust | capacity-availability, entitlement-ticketing |

`services/` also contains six future-scope skeletons that are not deployed and
not part of the current 23-service set: ancillary-service, dispatch,
disruption-recovery, transfer-management, waitlist, wallet-promotion. Treat
them as placeholders until a roadmap decision activates them.

## Verification baseline

`deploy/e2e/01..12` — 12 self-contained, rerunnable scripts, 159+ assertions:

seed(6) purchase(15) refund(15) change(8) fulfillment(11) risk(7)
fare-rules(29) notify-support(10) manual-action(7) account-gate(19)
legacy-acl(33), plus `12-restart.sh` for whole-cluster restart certification
(first full run: 191/0 — 42 snapshot tables, 117k rows identical across
deleting every pod in the namespace).

The legacy-acl script walks a complete order lifecycle exclusively through the
strangler facade's legacy-shaped endpoints. `deploy/e2e/12-restart.sh` pauses
the resident load generator if present, drains all transactional outboxes,
captures every PostgreSQL aggregate snapshot row count, deletes every pod in
the namespace, verifies the row counts survive unchanged, and then re-runs the
full 01–11 suite against the restarted cluster.

`deploy/loadgen/` contains the resident load generator. It can stay deployed in
the integration namespace for continuous traffic and is paused by the restart
certification script before the snapshot comparison.

## Governance

- Cross-service contracts live in `docs/08-contracts/` (api/, events/,
  messaging.md, shared-primitives.md, persistence.md). Rulings in those files
  are binding; PRs are reviewed field-by-field against them.
- Established invariants: deterministic event ids derived from consumed
  eventId/sourceRef; durable consumer-side eventId dedup; HTTP Idempotency-Key
  on every mutating endpoint; camelCase payload fields, SCREAMING_SNAKE enums,
  RFC3339 UTC timestamps.

Wave 12 delivered the SMTP notification channel (in-cluster mailpit,
TICKET_ISSUED intents deliver real email to synthetic per-traveler mailboxes;
IN_APP remains the default for all other intents) and the repo tidy pass.

The DLQ audit wave hardened event-handler failure taxonomy across consumers
(REQ-088..091 plus hotfixes): FATAL is reserved for events violating their own
contract; conformant events hitting unknown/advanced local state are
ack-skipped with a WARN; true transients retry. Every DLQ entry now carries
consumerGroup / failureReason / deadLetteredAt / attempts for attribution.

## Current backlog

1. Observability: OTel collector deployment in the integration cluster and
   OTLP traces from all 23 services via the language kits (REQ-095..098).
2. Provider/settlement follow-ups from live DLQ attribution: late
   ProviderReservationConfirmed on capacity-failed bookings, and the
   finance-settlement refund-lag reconciliation path (REQ-092/093).
3. Kit logging-quality audit for the remaining three kits (REQ-094).

Known accepted gaps after Phase 2: payment remains a simulated provider
boundary; legacy-acl rebook books the first leg only (caller follows up) — both
by explicit ruling.

## Historical note

Earlier revisions of this file (and `docs/04-implementation-plan/status.md`)
described the repo as a skeleton with WP-01 "rejected" briefs. That reflected
the pre-implementation planning phase and is obsolete; the authoritative
history is the merged PR trail on `refactor/greenfield-ddd`.
