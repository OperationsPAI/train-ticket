# Polyglot Language Selection

Last updated: 2026-06-28

## Decision Rules

1. Transactional aggregates, money, approvals, and audit-heavy workflows default to Java.
2. Protocol adapters, event ingestion, master-data lookup, and low-latency I/O default to Go.
3. Rule engines, planning, scoring, recovery, and analytics default to Python.
4. Strong invariant kernels such as inventory, entitlement, and fair queues default to Rust.
5. User-facing collaboration and payload-composition services default to TypeScript.

## Runtime And Framework Baseline

| Language | Runtime / Build | Service Framework | Version Policy |
|---|---|---|---|
| Java | Temurin JDK 25, Maven | Spring Boot 4.1.0 | Pin current latest stable parent in each service POM. |
| Go | Go 1.26 module directive, official Go 1.26.x toolchain | Gin v1.12.0 | Pin current latest module version in `go.mod`; lock via `go.sum`. |
| Python | Python >=3.11, uv | FastAPI 0.138.1 | Pin current latest packages in `pyproject.toml`; lock via `uv.lock`. |
| Rust | rustup stable, Cargo edition 2024 | Axum 0.8.9 | Pin current latest crate version in `Cargo.toml`; lock via `Cargo.lock`. |
| TypeScript | Node 26, npm | Fastify 5.9.0, TypeScript 6.0.3 | Pin current latest npm versions in `package.json`; lock via `package-lock.json`. |

## Assignments

| Service | Domain | Language | Phase | Rationale |
|---|---|---|---|---|
| `shared-kernel` | Shared Kernel / Platform | rust | phase-1-foundation | Rust keeps shared invariant examples strict while the normative contract remains language-neutral. |
| `place-network` | Place & Network | golang | phase-1-core | Go fits read-heavy master-data APIs and simple operational lookup paths. |
| `service-plan` | Service Plan | golang | phase-1-core | Go keeps schedule query services small, fast, and easy to operate. |
| `capacity-availability` | Capacity & Availability | rust | phase-1-core | Rust is appropriate for interval overlap and seat-hold invariants that must fail closed. |
| `fare-pricing` | Fare & Pricing | python | phase-1-core | Python keeps fare rules, explanation tooling, and later experimentation lightweight. |
| `trip-planning` | Trip Planning | python | phase-1-core | Python is a better fit for search heuristics, ranking, and explanation logic. |
| `offer-management` | Offer Management | typescript | phase-1-core | TypeScript fits user-facing offer API composition, TTL policy, and disclosure payloads. |
| `journey-order` | Journey Order | java | phase-1-core | Java is conservative for central transactional aggregates and long-lived domain models. |
| `booking-orchestration` | Booking Orchestration | java | phase-1-core | Java gives the saga layer explicit state transitions and stable integration testing options. |
| `payment` | Payment | java | phase-1-core | Java is a strong default for money state machines, idempotency, and audit-heavy flows. |
| `provider-integration` | Provider Integration | golang | phase-1-support | Go fits protocol adapters, webhooks, retry loops, and small deployable edge services. |
| `entitlement-ticketing` | Entitlement & Ticketing | rust | phase-1-core | Rust is appropriate for ticket entitlement lifecycle invariants and credential safety. |
| `fulfillment` | Fulfillment | golang | phase-1-limited | Go keeps event ingestion and station-side fact normalization operationally simple. |
| `post-sales` | Post Sales | java | phase-1-core | Java keeps refund/change case workflows explicit and compatible with transaction review tooling. |
| `notification` | Notification | typescript | phase-1-support | TypeScript fits template payloads, channel adapters, and product-facing notification policies. |
| `traveler-profile` | Traveler Profile | java | phase-1-support | Java keeps PII-heavy profile aggregates and validation policies explicit. |
| `risk-compliance` | Risk & Compliance | python | phase-1-support | Python supports rule experimentation, scoring, and evidence summarization without coupling source domains. |
| `account` | Account | typescript | phase-1-limited | TypeScript is suitable for identity/session edge APIs while traveler facts remain in Traveler Profile. |
| `admin-audit` | Admin & Audit | java | phase-1-support | Java is a conservative fit for approvals, audit retention, and authorization boundaries. |
| `customer-service` | Customer Service | typescript | phase-1-support | TypeScript fits collaborative case APIs and UI-adjacent timelines without owning target state. |
| `finance-settlement` | Finance Settlement | java | phase-1-limited | Java is appropriate for financial ledgers, reconciliation workflows, and audit consistency. |
| `reporting` | Reporting | python | phase-1-limited | Python is a pragmatic fit for metric definitions, event-derived views, and analytics validation. |
| `supplier-catalog` | Supplier Catalog | golang | phase-1-support | Go keeps supplier capability lookup compact and separate from provider runtime health. |
| `disruption-recovery` | Disruption Recovery | python | future-scope | Python fits recovery optimization and policy experimentation when this future domain is enabled. |
| `transfer-management` | Transfer Management | python | future-scope | Python fits connection-risk calculation and later protected-transfer recovery planning. |
| `ancillary-service` | Ancillary Service | typescript | future-scope | TypeScript fits productized add-ons and bundle-facing API composition. |
| `waitlist` | Waitlist | rust | future-scope | Rust fits fairness, mutual-exclusion, and queue-order invariants. |
| `wallet-promotion` | Wallet / Promotion | java | future-scope | Java is a conservative default for stored-value and promotion ledgers. |
| `dispatch` | Dispatch | golang | future-scope | Go fits real-time dispatch APIs, adapter calls, and low-latency state updates. |

## Boundaries

- Future-scope services are initialized only as placeholders. They must not be wired into Phase 1 acceptance gates until the DDD scope file changes.
- Shared Kernel is represented as a Rust reference module plus language-neutral contracts. Domain event payloads remain owned by their producing bounded context.
- A service may change language later only through a short architecture decision that updates this file and `service-catalog.json`.
