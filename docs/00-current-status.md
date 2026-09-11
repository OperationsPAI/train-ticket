# Current Project Status

Last updated: 2026-07-15

## Status: Phase 3 domain enrichment complete; consumer activation batch reconciled

The greenfield DDD rewrite is a working, end-to-end-verified system. All
Phase-1 work packages (WP-01..WP-23 of the accepted roadmap) are merged to
`refactor/greenfield-ddd`, deployed in the local kind integration environment,
and certified by the e2e suite.

Wave 11 completed the persistence baseline from
`docs/08-contracts/persistence.md` for the original deployed business-service
set: PostgreSQL aggregate snapshot rows with optimistic concurrency,
transactional outbox publishing, and durable `processed_events` consumer
dedup/idempotency. The deployed business-service set has since grown to 38.
Redis Streams remain the event bus; Redis is transport only, not a system of
record. It runs without a persistent volume — a Redis pod deletion wipes
streams and consumer groups by design, and services recover from PostgreSQL
(the transactional outbox re-publishes anything unpublished; consumer groups
are recreated on demand). `deploy/e2e/12-restart.sh` certifies exactly this.

## What runs today

38 deployed business services + Redis Streams + PostgreSQL, all deployed by the
Helm chart at `deploy/helm/train-ticket` (Helm is the only deployment path; the
kustomize overlay that used to sit alongside it has been retired and deleted),
each with a Dockerfile at `deploy/docker/<service>/Dockerfile`. `deploy/build-images.sh`
builds all 38 plus the loadgen; its image set is derived at run time from the
`train-ticket/*` images in the rendered release
(`deploy/render-manifests.sh`), so it cannot drift from what the cluster pulls.
`service-catalog.json` currently catalogs 33 of these contexts; the deployed set
also includes corporate-travel, group-booking, loyalty-membership,
marketing-campaign, and travel-insurance:

| Language | Services |
|---|---|
| Java (Boot 4) | admin-audit, booking-orchestration, finance-settlement, group-booking, journey-order, marketing-campaign, payment, post-sales, traveler-profile, wallet-promotion |
| Python (FastAPI) | corporate-travel, disruption-recovery, fare-pricing, identity-verification, legacy-acl, reporting, risk-compliance, transfer-management |
| Node (TS) | account, ancillary-service, customer-service, loyalty-membership, notification, offer-management, waitlist |
| Go | dispatch, fulfillment, payment-channel, place-network, provider-integration, seat-assignment, service-plan, supplier-catalog, travel-insurance |
| Rust | capacity-availability, entitlement-ticketing, invoicing, trip-planning |

The deployed `trip-planning` image builds the Rust `services/trip-planning-rs`
(its Dockerfile compiles that crate; a legacy Python `services/trip-planning`
tree remains in the repo but is not what ships).

Wave 15 activated **waitlist** (ADR-0002): sold-out demand now queues with
deadline, payment guarantee and fairness invariants, matches released
capacity (CapacityReleased gained an additive `segmentRef`), and fulfills by
running the normal quote→offer→order→payment chain on the customer's behalf
under persisted idempotency keys — staff reservation/ticketing steps stay
staff-driven. `deploy/e2e/14-waitlist.sh` covers the full lifecycle and is
part of the restart certification (latest run: 250/0 across 24 services).

Wave 18 activated **transfer-management**, completing the ADR-0002
activation arc: all six future-scope domains are live. TransferPlan /
Connection / ConnectionContract aggregates, system-reported segment status
driving the nine-state connection machine, and a protected missed-connection
closed loop through disruption-recovery (SYSTEM `MISSED_CONNECTION` reports,
`RecoveryCompleted` convergence). Restart certification spans 29 services and
suite 01-19 (latest run: 436/0, all DLQ zero at the time of that run -- see
the DLQ note under Wave 13). Wave 20 cleared the remaining functional debts: fulfillment publishes
SegmentArrived/Delayed/Cancelled (transfer-management consumes them
event-driven, HTTP reports remain as the ops fallback), transfer evaluation
reads place-network topology (degraded-not-blocking when place-network is
unavailable, times out, or returns 5xx; a client-supplied node ref that
place-network does not know is still rejected with `VALIDATION_FAILED` at
connection registration), and the
TransferRiskPolicy aggregate replaces the builtin-v1 fallback. No deferred
functional scope remains; latest restart certification: 470/0 (the accompanying
all-DLQ-zero claim no longer holds -- see the DLQ note under Wave 13).

Wave 17 activated **disruption-recovery** (ops-reported incidents,
RecoveryCase state machine, REFUND executed through post-sales with event
convergence, COMPENSATION through wallet-promotion `DISRUPTION_COMP`,
reaccommodation deferred to the transfer wave) and **ancillary-service**
(catalog/offer/order-item lifecycle with catalog pricing and
JourneyOrderCancelled linkage). Restart certification now spans 28 services
and suite 01-18 (latest run: 410/0).

Wave 16 activated **wallet-promotion** (benefit instruments with a 7-state
lifecycle, wallet ledgers, idempotent redemption; combined payment explicitly
deferred) and **dispatch** (full ride lifecycle behind a simulated supply
boundary, FAILED timeout closure added to the contract during the gate), plus
the trip-planning OCC fix. e2e 15/16 cover both; the restart certification now
spans 26 services and suite 01-16 (latest run: 296/0). All ADR-0002 skeletons are
now activated; `services/` contains no dormant skeletons.

## Phase 3 — Domain Enrichment (completed 2026-07-12)

22 domain-enrichment tasks (REQ-300..317 + REQ-150/151/152/232) delivered via
AgentM WorkGraph automation in a single session. Each existing service received
real-world business logic while maintaining backward API compatibility:

- **fare-pricing**: dynamic pricing (advance/peak/class/distance/capacity bands)
- **risk-compliance**: fraud scoring, velocity rules, scalper pattern detection
- **capacity-availability**: overbooking policy, class segmentation, snapshots
- **post-sales**: time-based refund/change policy engine with waterfall
- **seat-assignment**: preference scoring, hold lifecycle, class pools
- **waitlist**: auto-promotion saga with downstream chain integration
- **identity-verification**: multi-document, blacklist, duplicate-ticket
- **payment**: multi-channel routing with weighted fallback
- **transfer-management**: MCT enforcement, auto-rebooking
- **loyalty-membership**: points earning/redemption, tier system
- **disruption-recovery**: auto-rerouting, compensation policy
- **notification**: multi-channel delivery with fallback and rate limiting
- **service-plan**: seasonal schedules, delay propagation
- **travel-insurance**: multi-product catalog, claims processing
- **corporate-travel**: approval workflow, budget control
- **customer-service**: ticket escalation, SLA tracking
- **reporting**: real-time metrics, anomaly detection
- **finance-settlement**: daily reconciliation, supplier settlement

## Consumer activation and waitlist conformance (REQ-320..344, REQ-350..370)

The post-enrichment WorkGraph batch activated the remaining event consumers and
closed the waitlist conformance tail. Reporting now subscribes to every bounded
context stream for operational rollups. Notification, Customer Service, Journey
Order, Offer Management, Post Sales, Fulfillment, Finance Settlement, Fare
Pricing, Traveler Profile, Trip Planning, Payment, and Risk Compliance consume
the event subsets implemented in their stream configs and handlers. Waitlist
conformance work tightened the queue/payment-guarantee lifecycle and the
consumer activation docs have been reconciled so producer event rows distinguish
active subscribers from still-deferred touchpoints. No remaining deferred
consumer label should describe a service that is already subscribed and handling
the event.

Total WorkGraph tasks: done/=187. PRs #307..#329 merged.

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

`deploy/loadgen-go/` contains the resident load generator. It can stay deployed in
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
the integration cluster and all deployed services export OTLP traces through the
five language kits (HTTP server spans plus event-consumer spans carrying
stream/consumerGroup/eventId/eventType/correlationId; W3C traceparent on
outbound HTTP; env-driven and zero-overhead when OTEL_* is absent). The DLQ
second-order fixes (late provider confirmations now compensate via
SegmentBookingCancelled consumed by provider-integration; finance-settlement
refund-lag reconciliation) and the cross-kit silent-swallow audit landed in
the same wave.

> **Superseded on a long-running cluster.** "DLQ streams are trimmed to zero"
> described the state at the end of Wave 13 and is not the steady state. All
> eleven DLQ streams are non-empty on the live integration cluster (~23k entries),
> and `events:payment-channel:dlq` sits at its `MAXLEN 10000` cap, so it is
> silently discarding its oldest entries — any count read off it is a lower
> bound. The zero-growth-from-zero baseline cannot be used as a monitoring
> signal until the growth sources are closed; see "Operational findings" below.

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

Nothing queued. ADR-0002 activation and the subsequent consumer-activation /
waitlist-conformance batch are complete. Known accepted gaps after Phase 2:
payment remains a simulated provider boundary; legacy-acl rebook books the
first leg only (caller follows up) — both by explicit ruling.

## Operational findings

Observations from the live integration cluster that the wave notes above do not
reflect. These are properties of a long-running deployment, not of the last
certification run.

**DLQ growth has sources that are still open.** All eleven DLQ streams are
non-empty. The largest contributor was payment's classification of
`ChannelOrderSucceeded` against an already-expired intent: the intent window is
30s, the timeout sweeper runs every second, and a callback delayed behind any
real consumer backlog therefore arrives against an EXPIRED intent. That path now
opens a `LatePaymentCase` and the inbound handler acks, per the taxonomy ruling
above — FATAL is for events violating their own contract, and a channel-confirmed
collection does not. The remaining streams have not been attributed.

**Consumer backlog can be permanent, not just delayed.** `reporting` subscribes
to every context stream, and on several of them its group lag approaches the
whole stream length. The streams are trimmed with `MAXLEN ~`, so the oldest part
of that backlog is deleted from under the consumer rather than waiting for it.
Lag alone does not distinguish "behind" from "data gone".

**`failureReason` is not spelled consistently across kits.** DLQ entries carry
both `MaxDeliveries` and `MaxDeliveryAttempts` depending on which language kit
wrote them. Any alert or dashboard matching a single literal will silently miss
whichever half it did not pick.

**Event-type names drifted between producer and consumer, and nothing caught
it.** journey-order's handler switch matched `PaymentExpired`, a name no producer
publishes — payment emits `PaymentIntentExpired` (intent-scoped) and
`PaymentTimedOut` (order-scoped) on the same expiry. An unmatched type falls
through the switch's `default` to Success, so the order stayed in PENDING_PAYMENT
with no error, no DLQ entry and no log. The switch now matches `PaymentTimedOut`,
which is the one of the pair carrying the order reference.

There is no mechanical check for this class of bug: `contract_lint.py` walks
`services/` only, so the event-contract docs under `docs/08-contracts/events/`
are not validated against the code at all, and journey-order's
`RedisJourneyOrderSubscriptionsActionableTest` cross-checks its own two lists
against each other — both sides agreed on a name no producer used. Producer and
consumer names are only related by convention.

**`events:payment-channel:dlq` is capped at `MAXLEN 10000`.** It has been at the
cap, which means it drops its oldest entries as new ones arrive. Its length is a
lower bound on what was dead-lettered, not a count.

**Clearing one bottleneck exposes bugs that idle traffic hid.** Two defects only
became reachable once payment stopped lagging: corporate-travel dead-lettered
every retail `PaymentCaptured` (it required an `agreementId` the contract does not
carry), and a readiness-probe race in the shared Node kit crashed
offer-management outright. Both had been latent for as long as the throughput
defect masked them, so a quiet DLQ during a degraded period is not evidence of
correctness.

## Historical note

Earlier revisions of this file (and `docs/04-implementation-plan/status.md`)
described the repo as a skeleton with WP-01 "rejected" briefs. That reflected
the pre-implementation planning phase and is obsolete; the authoritative
history is the merged PR trail on `refactor/greenfield-ddd`.
