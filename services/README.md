# Service Skeletons

This directory contains one service skeleton per DDD bounded context.

The `Work Package` column is retained from the historical skeleton generator for
traceability only. Old `WP-01` / `WP-xx` identifiers are not the current rewrite
backlog unless a new plan explicitly regenerates and approves them.

| Service | Domain | Language | Phase | Work Package |
|---|---|---|---|---|
| `place-network` | Place & Network | golang | phase-1-core | WP-02 |
| `service-plan` | Service Plan | golang | phase-1-core | WP-03 |
| `capacity-availability` | Capacity & Availability | rust | phase-1-core | WP-05 |
| `fare-pricing` | Fare & Pricing | python | phase-1-core | WP-04 |
| `trip-planning` | Trip Planning | python | phase-1-core | WP-06 |
| `offer-management` | Offer Management | typescript | phase-1-core | WP-07 |
| `journey-order` | Journey Order | java | phase-1-core | WP-08 |
| `booking-orchestration` | Booking Orchestration | java | phase-1-core | WP-09 |
| `payment` | Payment | java | phase-1-core | WP-10 |
| `provider-integration` | Provider Integration | golang | phase-1-support | WP-11 |
| `entitlement-ticketing` | Entitlement & Ticketing | rust | phase-1-core | WP-12 |
| `fulfillment` | Fulfillment | golang | phase-1-limited | WP-13 |
| `post-sales` | Post Sales | java | phase-1-core | WP-14 |
| `notification` | Notification | typescript | phase-1-support | WP-15 |
| `traveler-profile` | Traveler Profile | java | phase-1-support | WP-16 |
| `risk-compliance` | Risk & Compliance | python | phase-1-support | WP-17 |
| `account` | Account | typescript | phase-1-limited | WP-18 |
| `admin-audit` | Admin & Audit | java | phase-1-support | WP-19 |
| `customer-service` | Customer Service | typescript | phase-1-support | WP-20 |
| `finance-settlement` | Finance Settlement | java | phase-1-limited | WP-21 |
| `reporting` | Reporting | python | phase-1-limited | WP-22 |
| `supplier-catalog` | Supplier Catalog | golang | phase-1-support | WP-02, WP-11 |
| `disruption-recovery` | Disruption Recovery | python | future-scope | future |
| `transfer-management` | Transfer Management | python | future-scope | future |
| `ancillary-service` | Ancillary Service | typescript | future-scope | future |
| `waitlist` | Waitlist | rust | future-scope | future |
| `wallet-promotion` | Wallet / Promotion | java | future-scope | future |
| `dispatch` | Dispatch | golang | future-scope | future |

The root `service-catalog.json` is the machine-readable source of this table.

## TypeScript Operational Foundation

TypeScript services expose app construction separately from service/domain exports. The shared skeleton contract is intentionally small:

- `createApp()` returns a Fastify instance without opening a socket.
- `/health` remains the compatibility health endpoint.
- `/livez` and `/readyz` expose the same current skeleton readiness status.
- `x-request-id` is propagated when supplied, generated otherwise, and returned on every response.
- `x-correlation-id` is propagated when supplied and otherwise defaults to the request id.
- Missing routes and unhandled errors use `{ "error": { "code", "message", "requestId", "correlationId" } }`.

Instrumentation is a seam, not a dependency, in this slice: `createApp({ onRequest })` lets future OpenTelemetry wiring observe request/correlation ids without adding heavy OTel packages to skeleton services.
