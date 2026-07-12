# WorkGraph Task Definitions for Domain Enrichment

## Overview

These tasks are designed for AgentM workgraph consumption. Each task references a design document and specifies clear deliverables.

## Wave 1: Core Transaction Path

### Task 1.1: fare-pricing-dynamic-engine
```
title: Implement dynamic pricing engine for fare-pricing
spec: docs/10-domain-enrichment/fare-pricing-enrichment.md
service: services/fare-pricing/
language: python
priority: P0
estimated_files: 7
dependencies: none

deliverables:
  - AdvancePurchaseTier, PeakPricingRule, SeatClassMultiplier in domain.py
  - Distance-based fare calculation
  - Capacity-driven dynamic adjustment (consume CapacitySnapshotUpdated events)
  - Updated API to accept seatClass, distanceKm, departureTime
  - Enriched FareQuoteComputed event breakdown
  - Unit tests for all tier/peak/dynamic calculations
  - Migration 002_pricing_enrichment.sql

acceptance:
  - python -m pytest tests/test_domain.py passes
  - Quote for 25-day advance booking returns 30% discount
  - Quote for peak hour returns +15% surcharge
  - Existing loadgen flow still works (backward compatible API)
```

### Task 1.2: risk-compliance-rules-engine
```
title: Implement fraud detection and scalper blocking for risk-compliance
spec: docs/10-domain-enrichment/risk-compliance-enrichment.md
service: services/risk-compliance/
language: java
priority: P0
estimated_files: 12
dependencies: none

deliverables:
  - VelocityRule, VelocityCounter domain models
  - RiskSignal, RiskScoreCalculator (0-100 scoring)
  - RiskEvaluation aggregate with PASS/CHALLENGE/BLOCK verdicts
  - ScalperPattern detection (SAME_ROUTE_BULK, RAPID_SEARCH_THEN_BOOK, etc.)
  - POST /api/v1/risk-evaluations endpoint (< 50ms target)
  - Redis-based sliding window velocity counters
  - Event consumption from journey-order, payment, post-sales, account
  - RiskEvaluationCompleted, RiskAlertRaised events
  - Unit tests for score calculator and velocity rules
  - Migration 002_risk_evaluation_tables.sql

acceptance:
  - 4 orders in 5 minutes from same account → BLOCK
  - New account + high-value order → CHALLENGE (score 30-60)
  - Normal single booking → PASS (score < 30)
  - Service builds and starts successfully
```

### Task 1.3: capacity-overbooking
```
title: Add overbooking strategy and capacity snapshots to capacity-availability
spec: docs/10-domain-enrichment/capacity-overbooking.md
service: services/capacity-availability/
language: rust
priority: P0
estimated_files: 5
dependencies: none

deliverables:
  - OverbookingPolicy struct in domain.rs
  - effective_capacity() method using overbooking percentage
  - CapacitySnapshotUpdated event on every mutation
  - WaitlistActivated / WaitlistCapacityFreed events
  - Seat class segmentation (ClassCapacity sub-pools)
  - GET /api/v1/capacity/segments/{ref}/snapshot endpoint
  - Unit tests for overbooking limits and waitlist triggers

acceptance:
  - cargo test passes
  - Pool with 100 seats + 5% overbooking accepts 105 holds
  - 106th hold triggers WaitlistActivated
  - CapacitySnapshotUpdated published after every hold/confirm/release
```

## Wave 2: Post-Sale and Seat

### Task 2.1: post-sales-refund-engine
```
title: Implement time-based refund and change policy engine for post-sales
spec: docs/10-domain-enrichment/post-sales-refund-policy.md
service: services/post-sales/
language: java
priority: P1
estimated_files: 10
dependencies: none

deliverables:
  - RefundPolicyEngine with time-based tiers (5/10/20% + non-refundable)
  - ChangePolicyEngine with fare difference calculation
  - RefundWaterfall (component-level refund/retain)
  - RefundClassification (VOLUNTARY vs INVOLUNTARY variants)
  - Integration into PostSalesCase aggregate
  - RefundFeeAssessed, ChangeFeeAssessed events
  - Unit tests for all tier calculations

acceptance:
  - mvn test passes
  - Refund 20 days before → 5% penalty
  - Refund 3 days before → 20% penalty
  - Involuntary refund → 0% penalty
  - Change with fare increase → correct net payable
```

### Task 2.2: seat-assignment-full
```
title: Full implementation of seat-assignment service
spec: docs/10-domain-enrichment/seat-assignment-impl.md
service: services/seat-assignment/
language: go
priority: P1
estimated_files: 12
dependencies: none

deliverables:
  - TrainConfig, CarConfig, Seat, SeatMap models
  - SeatInventory aggregate with hold/confirm/release lifecycle
  - AssignmentEngine with preference-based scoring
  - POST /api/v1/seat-assignments endpoint
  - GET availability endpoint
  - Hold expiry mechanism
  - Event consumption and publishing
  - Migration and unit tests

acceptance:
  - go test ./... passes
  - Window preference → seat A or E assigned
  - TOGETHER preference for 3 travelers → same row
  - Hold expires after timeout → seat available
```

### Task 2.3: waitlist-full
```
title: Full implementation of waitlist service with auto-promotion
spec: docs/10-domain-enrichment/waitlist-impl.md
service: services/waitlist/
language: typescript
priority: P1
estimated_files: 8
dependencies: [task-1.3-capacity-overbooking]

deliverables:
  - WaitlistEntry, WaitlistQueue domain models
  - PriorityCalculator (0-100 scoring)
  - PromotionOrchestrator saga
  - API endpoints (join, accept, cancel, view queue)
  - Event consumption (WaitlistCapacityFreed)
  - Event publishing (WaitlistEntryCreated, Promoted, Accepted)
  - Migration and unit tests

acceptance:
  - npm test passes
  - Platinum member ranks above non-member
  - WaitlistCapacityFreed triggers promotion of top entry
  - Expired offer promotes next in queue
```

## Execution Notes for AgentM

1. Each task operates on a single service directory — no cross-service file edits
2. All services use platform kits (java-kit, python-kit, go-kit, ts-kit, rust-kit) for infrastructure
3. New domain code goes in `domain/` (Java), `domain.py`/`domain.rs` (Python/Rust), `src/domain.ts` (TS), `internal/domain/` (Go)
4. Events must be published via the transactional outbox pattern (never directly to Redis)
5. All new API endpoints must have health check / readiness compatibility
6. Tests must be runnable without external dependencies (mock Redis/PG in tests)
7. Maintain backward compatibility — existing API contracts must not break
