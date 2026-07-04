# Service Skeletons

This directory contains one service skeleton per DDD bounded context.

The `Traceability ID` column records regenerated requirement slices or retained
legacy identifiers. Old `WP-01` / `WP-xx` identifiers are traceability-only in
runtime profiles and documentation; they are not the current rewrite backlog
unless a new plan explicitly regenerates and approves them.

`status-reconciliation-needed` means the service README and local code expose a
REQ status, but `docs/00-current-status.md` has not yet promoted that REQ as an
authoritative current active slice. Do not treat those services as silently
active until the status document is reconciled.

| Service | Domain | Language | Phase | Status | Traceability ID |
|---|---|---|---|---|---|
| `place-network` | Place & Network | golang | phase-1-core | tested | REQ-004 |
| `service-plan` | Service Plan | golang | phase-1-core | tested | REQ-005 |
| `capacity-availability` | Capacity & Availability | rust | phase-1-core | tested | REQ-006 |
| `fare-pricing` | Fare & Pricing | python | phase-1-core | tested | REQ-007 |
| `trip-planning` | Trip Planning | python | phase-1-search-foundation | implemented-foundation | REQ-008-Trip-Planning-search-foundation |
| `offer-management` | Offer Management | typescript | phase-1-offer-domain-foundation | implemented-foundation | REQ-009-Offer-Management-domain-foundation |
| `journey-order` | Journey Order | java | phase-1-domain-foundation | status-reconciliation-needed | REQ-010-Journey-Order-domain-foundation |
| `booking-orchestration` | Booking Orchestration | java | phase-1-core | status-reconciliation-needed | REQ-011 |
| `payment` | Payment | java | phase-1-domain-foundation | status-reconciliation-needed | REQ-012-Payment-domain-foundation |
| `provider-integration` | Provider Integration | golang | phase-1-acl-foundation | status-reconciliation-needed | REQ-015 |
| `entitlement-ticketing` | Entitlement & Ticketing | rust | phase-1-domain-foundation | status-reconciliation-needed | REQ-013 |
| `fulfillment` | Fulfillment | golang | phase-1-limited | skeleton | WP-13 |
| `post-sales` | Post Sales | java | phase-1-core | status-reconciliation-needed | REQ-014 |
| `notification` | Notification | typescript | phase-1-support | skeleton | WP-15 |
| `traveler-profile` | Traveler Profile | java | phase-1-support | skeleton | WP-16 |
| `risk-compliance` | Risk & Compliance | python | phase-1-support | skeleton | WP-17 |
| `account` | Account | typescript | phase-1-limited | skeleton | WP-18 |
| `admin-audit` | Admin & Audit | java | phase-1-support | skeleton | WP-19 |
| `customer-service` | Customer Service | typescript | phase-1-support | skeleton | WP-20 |
| `finance-settlement` | Finance Settlement | java | phase-1-limited | skeleton | WP-21 |
| `reporting` | Reporting | python | phase-1-limited | skeleton | WP-22 |
| `supplier-catalog` | Supplier Catalog | golang | phase-1-support | skeleton | WP-02, WP-11 |
| `disruption-recovery` | Disruption Recovery | python | future-scope | placeholder-skeleton | future |
| `transfer-management` | Transfer Management | python | future-scope | placeholder-skeleton | future |
| `ancillary-service` | Ancillary Service | typescript | future-scope | placeholder-skeleton | future |
| `waitlist` | Waitlist | rust | future-scope | placeholder-skeleton | future |
| `wallet-promotion` | Wallet / Promotion | java | future-scope | placeholder-skeleton | future |
| `dispatch` | Dispatch | golang | future-scope | placeholder-skeleton | future |

The root `service-catalog.json` is the machine-readable source of this table.

## TypeScript Operational Foundation

TypeScript services expose app construction, bootstrap defaults, service profile metadata, and domain exports separately. The shared skeleton contract is intentionally small:

- `createApp()` returns a Fastify instance without opening a socket.
- `bootstrap()` applies environment/default host and port settings for runtime startup.
- `/health` remains the compatibility health endpoint and includes the service profile.
- `/live`, `/livez`, `/ready`, and `/readyz` expose the current skeleton probe status.
- `/metadata` exposes service profile metadata and the observability contract.
- `x-request-id` is propagated when supplied, generated otherwise, and returned on every response.
- `x-correlation-id` is propagated when supplied and otherwise defaults to the request id.
- Missing routes and unhandled errors use `{ "error": { "code", "message", "requestId", "correlationId" } }`.

Instrumentation is a seam, not a dependency, in this slice: `createApp({ onRequest, startSpan })` lets future OpenTelemetry wiring observe request/correlation ids and span completion without adding heavy OTel packages to skeleton services. The default is no-op and requires no external infrastructure in tests.

## OpenTelemetry Collection Baseline

All service runtimes target the repository OpenTelemetry collection contract in
`docs/07-observability/README.md`. The collector baseline is intentionally
centralized under `platform/observability/` so language services can keep tests
no-op by default while production or local runtime adapters export OTLP signals
to the same endpoint shape.

When enabling real SDK instrumentation, use the service id as
`OTEL_SERVICE_NAME`, include `service.namespace=train-ticket` in
`OTEL_RESOURCE_ATTRIBUTES`, and export to the local collector over OTLP HTTP or
gRPC.
