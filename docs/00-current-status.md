# Current Project Status

Last updated: 2026-07-08

## Status: Phase 1 complete; Phase 2 hardening complete (persistence, channels, DLQ audit, observability, tracing, coverage)

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
| Java (Boot 4) | admin-audit, booking-orchestration, finance-settlement, journey-order, payment, post-sales, traveler-profile, wallet-promotion |
| Python (FastAPI) | fare-pricing, legacy-acl, reporting, risk-compliance, trip-planning |
| Node (TS) | account, customer-service, notification, offer-management |
| Go | dispatch, fulfillment, place-network, provider-integration, service-plan, supplier-catalog |
| Rust | capacity-availability, entitlement-ticketing, waitlist |

Wave 15 activated **waitlist** (ADR-0002): sold-out demand now queues with
deadline, payment guarantee and fairness invariants, matches released
capacity (CapacityReleased gained an additive `segmentRef`), and fulfills by
running the normal quote→offer→order→payment chain on the customer's behalf
under persisted idempotency keys — staff reservation/ticketing steps stay
staff-driven. `deploy/e2e/14-waitlist.sh` covers the full lifecycle and is
part of the restart certification (latest run: 250/0 across 24 services).

Wave 16 activated **wallet-promotion** (benefit instruments with a 7-state
lifecycle, wallet ledgers, idempotent redemption; combined payment explicitly
deferred) and **dispatch** (full ride lifecycle behind a simulated supply
boundary, FAILED timeout closure added to the contract during the gate), plus
the trip-planning OCC fix. e2e 15/16 cover both; the restart certification now
spans 26 services and suite 01-16 (latest run: 296/0). `services/` retains
three future-scope skeletons per ADR-0002: ancillary-service,
disruption-recovery, transfer-management.

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

Wave 13 delivered the observability baseline: an OTel collector runs in
the integration cluster and all 23 services export OTLP traces through the
five language kits (HTTP server spans plus event-consumer spans carrying
stream/consumerGroup/eventId/eventType/correlationId; W3C traceparent on
outbound HTTP; env-driven and zero-overhead when OTEL_* is absent). The DLQ
second-order fixes (late provider confirmations now compensate via
SegmentBookingCancelled consumed by provider-integration; finance-settlement
refund-lag reconciliation) and the cross-kit silent-swallow audit landed in
the same wave. DLQ streams are trimmed to zero — the monitoring baseline is
zero-growth-from-zero.

Wave 14 completed distributed tracing end-to-end: optional W3C
traceparent/tracestate on the wire envelope (messaging.md ruling), injected
by all five language kits at envelope creation (outbox-safe) and used as the
remote parent of consumer spans — a single business trace now spans both the
HTTP hop and every event hop. Follow-up work closed the coverage tail: a
repo-wide HTTP contract-drift audit (one drift found and documented), removal
of provider-integration's dead internal HTTP command endpoints (its command
surface is event-only by ruling), a long-tail loadgen prober (real-ID read
probes, lifecycle branches, low-frequency ops actor), and an observability
e2e smoke (13-observability.sh, receiver-counter based).

## Current backlog

Nothing queued. Next per ADR-0002: disruption-recovery + ancillary-service,
then transfer-management. Known small debts: loadgen's ConfigMap is created
imperatively by deploy/loadgen/run.sh (a stale copy masked new journeys for a
day — should move under kustomize), and Wallet/Promotion's
finance/notification consumers remain documented-deferred.

Known accepted gaps after Phase 2: payment remains a simulated provider
boundary; legacy-acl rebook books the first leg only (caller follows up) — both
by explicit ruling.

## Historical note

Earlier revisions of this file (and `docs/04-implementation-plan/status.md`)
described the repo as a skeleton with WP-01 "rejected" briefs. That reflected
the pre-implementation planning phase and is obsolete; the authoritative
history is the merged PR trail on `refactor/greenfield-ddd`.
