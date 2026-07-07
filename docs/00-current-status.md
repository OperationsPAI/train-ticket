# Current Project Status

Last updated: 2026-07-07

## Status: Phase 1 functionally complete; Phase 2 (engineering hardening) in progress

The greenfield DDD rewrite has delivered a working, end-to-end-verified
system. All Phase-1 work packages (WP-01..WP-23 of the accepted roadmap)
are implemented, merged to `refactor/greenfield-ddd`, and certified on the
live kind cluster.

## What runs today

23 deployed business services + redis (event bus), all under
`deploy/k8s/`, images built by `deploy/build-images.sh`:

| Language | Services |
|---|---|
| Java (Boot 4) | admin-audit, booking-orchestration, finance-settlement, journey-order, payment, post-sales, traveler-profile |
| Python (FastAPI) | fare-pricing, legacy-acl, reporting, risk-compliance, trip-planning |
| Node (TS) | account, customer-service, notification, offer-management |
| Go | fulfillment, place-network, provider-integration, service-plan, supplier-catalog |
| Rust | capacity-availability, entitlement-ticketing |

`services/` also contains six future-scope skeletons that are NOT
deployed and NOT part of Phase 1: ancillary-service, dispatch,
disruption-recovery, transfer-management, waitlist, wallet-promotion.
Treat them as placeholders until a roadmap decision activates them.

## Verification baseline

`deploy/e2e/01..11` — 11 self-contained, rerunnable scripts, 159
asserts, all green at certification (2026-07-07):

seed(6) purchase(15) refund(15) change(8) fulfillment(11) risk(7)
fare-rules(29) notify-support(9) manual-action(7) account-gate(19)
legacy-acl(33).

The legacy-acl script walks a complete order lifecycle exclusively
through the strangler facade's legacy-shaped endpoints.

## Governance

- Cross-service contracts live in `docs/08-contracts/` (api/, events/,
  messaging.md, shared-primitives.md, persistence.md). Rulings in those
  files are binding; PRs are reviewed field-by-field against them.
- Established invariants: deterministic event ids derived from consumed
  eventId/sourceRef; consumer-side eventId dedup; HTTP Idempotency-Key
  on every mutating endpoint; camelCase payload fields,
  SCREAMING_SNAKE enums, RFC3339 UTC timestamps.

## Phase 2 — engineering hardening (current backlog)

Ruling: `docs/08-contracts/persistence.md`. In flight:

1. PostgreSQL persistence (aggregate snapshots + optimistic concurrency)
   replacing all in-memory state, service by service.
2. Transactional outbox + durable consumer dedup (replaces the
   rollback+503 publish pattern).
3. Redis AOF for bus durability.
4. Notification real channel adapters (SMTP via in-cluster mailpit).
5. Repo tidy: stale docs refreshed, future-scope skeletons marked.

Known accepted gaps after Phase 2: payment remains a simulated provider
boundary; legacy-acl rebook books the first leg only (caller follows up)
— both by explicit ruling.

## Historical note

Earlier revisions of this file (and `docs/04-implementation-plan/status.md`)
described the repo as a skeleton with WP-01 "rejected" briefs. That
reflected the pre-implementation planning phase and is obsolete; the
authoritative history is the merged PR trail on `refactor/greenfield-ddd`.
