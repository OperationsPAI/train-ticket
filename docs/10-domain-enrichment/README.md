# Domain Logic Enrichment Plan

## Background

The train-ticket system has 38 microservices with ~105K lines of production code (5 languages). The DDD design docs (`docs/02-domains/`) specify rich domain models, but most implementations are thin CRUD+outbox scaffolds. This enrichment plan bridges the gap between spec and implementation to make the system realistic for stress testing and correctness verification.

## Current State Assessment

### Services with real domain logic (needs enrichment)

| Service | Language | Domain Lines | Gap |
|---------|----------|-------------|-----|
| capacity-availability | Rust | 1,293 | Has hold/confirm/release but no overbooking, no waitlist integration |
| post-sales | Java | 1,351 | Has PostSalesCase state machine but no refund policy engine |
| fare-pricing | Python | 663 | Has Money/rules/quotes but no dynamic pricing, no revenue management |
| offer-management | TypeScript | 680 | Has upstream state but needs price guarantee enforcement |
| journey-order | Java | 957 | Has lifecycle FSM but needs cancellation policy enforcement |
| booking-orchestration | Java | 1,028 | Has saga but needs timeout/compensation enrichment |

### Services that are empty shells (needs full implementation)

| Service | Language | Domain Lines | Status |
|---------|----------|-------------|--------|
| risk-compliance | Java | 0 | Only scaffold, no rules engine |
| seat-assignment | Go | 0 | No implementation at all |
| waitlist | TypeScript | 0 | No implementation at all |
| notification | Java | 0 | Only scaffold, no delivery logic |

## Enrichment Waves

### Wave 1: Core Transaction Path (highest impact on stress test)
1. **fare-pricing** — Dynamic pricing engine with advance-purchase tiers
2. **risk-compliance** — Velocity checks, fraud scoring, scalper detection
3. **capacity-availability** — Overbooking algorithm, waitlist trigger

### Wave 2: Post-Sale and Seat
4. **post-sales** — Time-based refund policy engine, change fee calculation
5. **seat-assignment** — Car layout model, preference-based assignment
6. **waitlist** — Priority scoring, auto-promotion on cancellation

### Wave 3: Core Supporting Services
7. **identity-verification** — Real-name enforcement, duplicate-ticket constraint, blacklist
8. **payment** — Multi-channel routing, timeout auto-cancel, partial refund
9. **transfer-management** — MCT enforcement, missed-connection auto-rebooking

### Wave 4: Business Depth
10. **loyalty-membership** — Points earning/redemption, tier upgrade/downgrade, expiry
11. **disruption-recovery** — Auto-rerouting algorithm, compensation policy, batch processing
12. **notification** — Multi-channel fallback, template rendering, rate limiting

### Wave 5: Operational & Financial
13. **service-plan** — Seasonal schedules (春运/暑运), delay propagation, temporary trains
14. **travel-insurance** — Multi-product catalog, auto-payout on delay, claims workflow
15. **corporate-travel** — Approval workflow, budget control, policy enforcement
16. **customer-service** — Ticket escalation, SLA tracking, compensation authorization
17. **reporting** — Real-time metrics, revenue analytics, anomaly detection
18. **finance-settlement** — Daily reconciliation, supplier revenue split, tax handling

## Design Document Index

Each enrichment task has its own spec document:

### Wave 1-2: Core Transaction Path
- [fare-pricing-enrichment.md](./fare-pricing-enrichment.md) — Dynamic pricing & revenue management
- [risk-compliance-enrichment.md](./risk-compliance-enrichment.md) — Fraud detection & scalper blocking
- [capacity-overbooking.md](./capacity-overbooking.md) — Overbooking & waitlist trigger
- [post-sales-refund-policy.md](./post-sales-refund-policy.md) — Time-based refund/change policies
- [seat-assignment-impl.md](./seat-assignment-impl.md) — Seat map & preference assignment
- [waitlist-impl.md](./waitlist-impl.md) — Priority queue & auto-promotion

### Wave 3: Core Supporting
- [identity-verification-enrichment.md](./identity-verification-enrichment.md) — Real-name enforcement & blacklist
- [payment-multichannel.md](./payment-multichannel.md) — Multi-channel payment & refund
- [transfer-management-enrichment.md](./transfer-management-enrichment.md) — Connection constraints & auto-rebooking

### Wave 4: Business Depth
- [loyalty-membership-enrichment.md](./loyalty-membership-enrichment.md) — Points & tier system
- [disruption-recovery-enrichment.md](./disruption-recovery-enrichment.md) — Auto-rerouting & compensation
- [notification-enrichment.md](./notification-enrichment.md) — Multi-channel delivery & rate limiting

### Wave 5: Operational & Financial
- [service-plan-seasonal.md](./service-plan-seasonal.md) — Seasonal schedules & delay propagation
- [travel-insurance-enrichment.md](./travel-insurance-enrichment.md) — Multi-product & claims processing
- [corporate-travel-enrichment.md](./corporate-travel-enrichment.md) — Approval workflow & budget control
- [customer-service-enrichment.md](./customer-service-enrichment.md) — Escalation & SLA tracking
- [reporting-enrichment.md](./reporting-enrichment.md) — Real-time metrics & anomaly detection
- [finance-settlement-enrichment.md](./finance-settlement-enrichment.md) — Reconciliation & supplier settlement

## WorkGraph Integration

Each spec is structured for AgentM workgraph consumption:
1. **Context** — existing code paths, files to read
2. **Requirements** — specific domain rules to implement
3. **Interface contracts** — events consumed/produced, API changes
4. **Test criteria** — how to verify correctness
5. **Files to modify** — exact paths
