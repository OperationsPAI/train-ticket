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

### Wave 3: Operational Enrichment
7. **notification** — Multi-channel delivery with fallback, rate limiting
8. **journey-order** — Cancellation policy enforcement, timeline enrichment
9. **booking-orchestration** — Timeout handling, partial-failure compensation

## Design Document Index

Each enrichment task has its own spec document:

- [fare-pricing-enrichment.md](./fare-pricing-enrichment.md) — Dynamic pricing & revenue management
- [risk-compliance-enrichment.md](./risk-compliance-enrichment.md) — Fraud detection & scalper blocking
- [capacity-overbooking.md](./capacity-overbooking.md) — Overbooking & waitlist trigger
- [post-sales-refund-policy.md](./post-sales-refund-policy.md) — Time-based refund/change policies
- [seat-assignment-impl.md](./seat-assignment-impl.md) — Seat map & preference assignment
- [waitlist-impl.md](./waitlist-impl.md) — Priority queue & auto-promotion

## WorkGraph Integration

Each spec is structured for AgentM workgraph consumption:
1. **Context** — existing code paths, files to read
2. **Requirements** — specific domain rules to implement
3. **Interface contracts** — events consumed/produced, API changes
4. **Test criteria** — how to verify correctness
5. **Files to modify** — exact paths
