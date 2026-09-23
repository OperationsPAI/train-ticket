# Business services

Last updated: 2026-09-24.

Each directory contains its owning business implementation and module README.
Use [current status](../docs/00-current-status.md) for deployment and acceptance, and [TODO](../docs/TODO.md) for all remaining work.
Catalog requirement and phase labels are metadata, not deployment gates.

| Language | Service directories |
|---|---|
| Java | `admin-audit`, `booking-orchestration`, `finance-settlement`, `group-booking`, `journey-order`, `marketing-campaign`, `payment`, `post-sales`, `traveler-profile`, `wallet-promotion` |
| Go | `dispatch`, `fulfillment`, `payment-channel`, `place-network`, `provider-integration`, `seat-assignment`, `service-plan`, `supplier-catalog`, `travel-insurance` |
| Python | `corporate-travel`, `disruption-recovery`, `fare-pricing`, `reporting`, `risk-compliance`, `transfer-management`, `identity-verification` |
| Rust | `capacity-availability`, `entitlement-ticketing`, `invoicing`, `trip-planning-rs` |
| TypeScript | `account`, `ancillary-service`, `customer-service`, `loyalty-membership`, `notification`, `offer-management`, `waitlist` |

The deployment image `trip-planning` builds the `trip-planning-rs` directory.
Image definitions are in `deploy/docker/`; the chart determines enabled workloads.
Services exchange owning APIs and domain events using their language platform kit.
HTTP health, readiness, correlation, idempotency, PostgreSQL persistence and Redis delivery are configured by the service and platform runtime.

Capacity combined supply and notification durable sending are unpublished worktree implementations at this handoff.
Their README files distinguish those paths from the deployed release.
