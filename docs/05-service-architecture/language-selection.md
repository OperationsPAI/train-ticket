# Languages And Source Paths

Updated: September 24, 2026.

The deployment baseline is Helm revision 279, with evidence in [current project status](../00-current-status.md).
The tables below match the checked source tree and build manifests.
The catalog contains 32 business services and two platform modules.
The source tree contains 37 business service directories, including the five additional services listed below.
Unfinished capabilities and deployment work are tracked in [TODO](../TODO.md).

## Catalog Services

Language values use the spelling in [service-catalog.json](../../service-catalog.json) and are checked against each service's build manifest.
Each path opens the corresponding service source directory.
Registered HTTP entry points and business responsibilities are indexed in [domain ownership](../03-ddd-final/domain-reduce-status.md).

| Service ID | Source path | Language | Responsibility |
|---|---|---|---|
| `place-network` | [services/place-network](../../services/place-network/) | golang | Places and transport nodes. |
| `service-plan` | [services/service-plan](../../services/service-plan/) | golang | Schedules, service segments and dated service changes. |
| `capacity-availability` | [services/capacity-availability](../../services/capacity-availability/) | rust | Availability, interval Holds, confirmation and release. |
| `fare-pricing` | [services/fare-pricing](../../services/fare-pricing/) | python | Fare rules, quotes and post sales fee calculations. |
| `trip-planning` | [services/trip-planning-rs](../../services/trip-planning-rs/) | rust | Itinerary search and candidate retrieval. |
| `offer-management` | [services/offer-management](../../services/offer-management/) | typescript | Offer creation and immutable quote references. |
| `journey-order` | [services/journey-order](../../services/journey-order/) | java | Commercial order state and accepted purchase source. |
| `booking-orchestration` | [services/booking-orchestration](../../services/booking-orchestration/) | java | Booking Saga, segment reservation and change compensation. |
| `payment` | [services/payment](../../services/payment/) | java | Platform payment intents, refunds and monetary facts. |
| `payment-channel` | [services/payment-channel](../../services/payment-channel/) | golang | Channel orders, channel refunds and channel statements. |
| `provider-integration` | [services/provider-integration](../../services/provider-integration/) | golang | Provider reservation models and external fact normalization. |
| `entitlement-ticketing` | [services/entitlement-ticketing](../../services/entitlement-ticketing/) | rust | Ticket issuance, issue preconditions and entitlement lifecycle. |
| `fulfillment` | [services/fulfillment](../../services/fulfillment/) | golang | Boarding, completion, no show and segment status facts. |
| `post-sales` | [services/post-sales](../../services/post-sales/) | java | Refund, change and compensation cases and execution. |
| `notification` | [services/notification](../../services/notification/) | typescript | Event driven delivery, templates and notification trail. |
| `traveler-profile` | [services/traveler-profile](../../services/traveler-profile/) | java | Traveler records, documents and profile eligibility. |
| `risk-compliance` | [services/risk-compliance](../../services/risk-compliance/) | python | Risk assessments, evaluations, blocks and overrides. |
| `account` | [services/account](../../services/account/) | typescript | Accounts, account status, preferences and closure entry. |
| `admin-audit` | [services/admin-audit](../../services/admin-audit/) | java | Operators, approvals, manual actions and audit trail. |
| `customer-service` | [services/customer-service](../../services/customer-service/) | typescript | Support cases, evidence and controlled action requests. |
| `finance-settlement` | [services/finance-settlement](../../services/finance-settlement/) | java | Revenue recognition, settlement, reconciliation and benefit costs. |
| `reporting` | [services/reporting](../../services/reporting/) | python | Event projections, operational metrics and gross captured payment reporting. |
| `supplier-catalog` | [services/supplier-catalog](../../services/supplier-catalog/) | golang | Suppliers, carriers and supply contracts. |
| `disruption-recovery` | [services/disruption-recovery](../../services/disruption-recovery/) | python | Disruptions, recovery cases and option selection. |
| `transfer-management` | [services/transfer-management](../../services/transfer-management/) | python | Connections, MCT rules, protection contracts and risk policies. |
| `ancillary-service` | [services/ancillary-service](../../services/ancillary-service/) | typescript | Ancillary offers, order items and fulfillment facts. |
| `seat-assignment` | [services/seat-assignment](../../services/seat-assignment/) | golang | Seat maps, physical allocations and standing allocation evidence. |
| `invoicing` | [services/invoicing](../../services/invoicing/) | rust | Invoice titles, electronic invoices, red flushes and itinerary receipts. |
| `waitlist` | [services/waitlist](../../services/waitlist/) | typescript | Waitlist requests, queue entries and acceptance. |
| `wallet-promotion` | [services/wallet-promotion](../../services/wallet-promotion/) | java | Benefits, wallet balances, reservations and redemptions. |
| `dispatch` | [services/dispatch](../../services/dispatch/) | golang | Ride requests, assignment, ETA and ride lifecycle. |
| `identity-verification` | [services/identity-verification](../../services/identity-verification/) | python | Credential verification, eligibility certificates and purchase limit facts. |

## Additional Services In Source

These services have source directories, build manifests and registered API handlers.
Their service IDs are absent from the checked catalog.
Catalog reconciliation is tracked in [TODO T10](../TODO.md#t10-reconcile-repository-metadata-and-static-checks).
Remaining feature work and acceptance are tracked in [TODO](../TODO.md).

| Service ID | Source path | Language | Responsibility |
|---|---|---|---|
| `corporate-travel` | [services/corporate-travel](../../services/corporate-travel/) | python | Corporate agreements, authorizations, policy checks and monthly billing. |
| `group-booking` | [services/group-booking](../../services/group-booking/) | java | Group booking requests, members, confirmation and cancellation. |
| `loyalty-membership` | [services/loyalty-membership](../../services/loyalty-membership/) | typescript | Membership, points, tier evaluation and redemption. |
| `marketing-campaign` | [services/marketing-campaign](../../services/marketing-campaign/) | java | Campaigns, coupon templates and issuance batches. |
| `travel-insurance` | [services/travel-insurance](../../services/travel-insurance/) | golang | Policies and claim decisions. |

## Runtime And Build

Versions below describe checked manifests and image definitions.
Dependency files and lockfiles remain the source for exact resolved versions.

| Language | Runtime and build | HTTP framework | Evidence |
|---|---|---|---|
| Java | JDK 25, Maven | Spring Boot 4.1.0 | Service `pom.xml` files. |
| Go | Go 1.26 module directive | Gin 1.12.0 | Service `go.mod` and `go.sum` files. |
| Python | Python >=3.11, uv | FastAPI 0.138.1 through python-kit | Service `pyproject.toml`, `uv.lock` and [python-kit manifest](../../platform/python-kit/pyproject.toml). |
| Rust | Cargo edition 2024 | Axum 0.8.9 | Service `Cargo.toml`, `Cargo.lock` and image definitions. |
| TypeScript | Node 26, npm, TypeScript 6.0.3 | Fastify 5.9.0 | Service `package.json`, `package-lock.json` and [Dockerfiles](../../deploy/docker/). |

Java services express transactional workflows and money state machines.
Go services cover master data, channel protocols and allocation APIs.
Python services express pricing, identity, risk, recovery and reporting rules.
Rust services cover search computation, inventory and entitlement invariants, and electronic invoice state.
TypeScript services cover offer composition, account and support APIs, notifications, waitlist and membership.
The selected implementation language does not transfer business ownership between services.

## Shared Modules

| Module | Path | Language | Catalog entry |
|---|---|---|---|
| `shared-kernel` | [platform/shared-kernel-rust](../../platform/shared-kernel-rust/) | rust | Present. |
| `go-runtime` | [platform/go-runtime](../../platform/go-runtime/) | golang | Present. |
| `go-kit` | [platform/go-kit](../../platform/go-kit/) | golang | Absent. |
| `java-kit` | [platform/java-kit](../../platform/java-kit/) | java | Absent. |
| `python-kit` | [platform/python-kit](../../platform/python-kit/) | python | Absent. |
| `rust-kit` | [platform/rust-kit](../../platform/rust-kit/) | rust | Absent. |
| `ts-kit` | [platform/ts-kit](../../platform/ts-kit/) | typescript | Absent. |

Shared modules provide runtime, messaging, storage and common value support.
Domain event payloads and business state remain owned by the producing service.
Capacity's new combined supply implementation is uncommitted and undeployed.
Notification's durable delivery queue is WIP and undeployed.
Work required for these capabilities is tracked in [TODO](../TODO.md).
