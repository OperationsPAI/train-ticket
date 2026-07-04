# Conformance Gaps — Field-Level Mismatches

Last updated: 2026-07-04

This document lists concrete mismatches between the contract specification
(`docs/08-contracts/`) and the current service implementations. Each gap MUST
be resolved before integration testing (联调).

## 1. Money Representation

### Severity: HIGH — All services must use `minorUnits: i64`

| # | Service | File | Issue | Canonical Form |
|---|---|---|---|---|
| GAP-001 | fare-pricing | `services/fare-pricing/src/fare_pricing/domain.py` | `Money` uses `Decimal` amount with implicit two-decimal-place semantics. | `minorUnits: i64` |
| GAP-002 | offer-management | `services/offer-management/src/domain.ts` | `Money` type uses `amount: number` (float). | `minorUnits: i64` |
| GAP-003 | journey-order | `services/journey-order/.../domain/Money.java` | Uses `BigDecimal` amount. | `minorUnits: i64` |
| GAP-004 | payment | `services/payment/.../domain/Money.java` | Uses `BigDecimal` amount. | `minorUnits: i64` |

## 2. ID Prefix Conventions

### Severity: HIGH — Services must adopt canonical ID prefixes

| # | Service | File | Issue | Canonical Prefix |
|---|---|---|---|---|
| GAP-005 | shared-kernel-rust | `platform/shared-kernel-rust/src/lib.rs` | Generic wrappers without prefix validation. | `plc-`, `tvl-`, `seg-` |
| GAP-006 | capacity-availability | `services/capacity-availability/src/lib.rs` | Free-form strings without prefix convention. | `hold-<uuid>` |
| GAP-007 | booking-orchestration | `SegmentBooking.java` | `UUID.randomUUID()` without prefix. | `sb-<uuid>` |
| GAP-008 | payment | `PaymentIntent.java` | `UUID.randomUUID()` without prefix. | `pi-<uuid>` |
| GAP-009 | journey-order | `JourneyOrder.java` | `UUID.randomUUID()` without prefix. | `ord-<uuid>` |

## 3. Event Envelope Compliance

### Severity: HIGH — All cross-context events must use EventEnvelope

| # | Service | File | Issue | Required |
|---|---|---|---|---|
| GAP-010 | journey-order | `JourneyOrderCreated.java` | Events use `EventMetadata`, not `EventEnvelope`. | `EventEnvelope` with `eventId`, `eventType`, `occurredAt`, `correlationId`, `causationId`, `producer`, `schemaVersion`, `payload`. |
| GAP-011 | payment | `PaymentIntent.java` | Events use `PaymentEvent` with `EventMetadata`. | Same as GAP-010. |
| GAP-012 | booking-orchestration | `BookingSaga.java` | Events use `DomainEvent` base. | Same as GAP-010. |

## 4. Serialisation Conventions

### Severity: MEDIUM — JSON field naming and enum casing

| # | Service | File | Issue | Required |
|---|---|---|---|---|
| GAP-013 | trip-planning | `domain.py` | Accepts both camelCase and snake_case. | All JSON fields MUST be camelCase. |
| GAP-014 | offer-management | `domain.ts` | Enums are PascalCase (`"Quoted"`). | Enums MUST be SCREAMING_SNAKE. |

## 5. Missing Event Publications

### Severity: MEDIUM

| # | Context | Event | Status |
|---|---|---|---|
| GAP-015 | booking-orchestration | `BookingSagaStarted` | Not published as cross-context event. |
| GAP-016 | payment | `PaymentIntentCreated` | Not published as cross-context `EventEnvelope`. |
| GAP-017 | payment | `RefundSettled` | Not published as cross-context `EventEnvelope`. |
| GAP-018 | capacity-availability | `AvailabilitySnapshot` | Not published as standalone event (currently only a read-model projection). |

## 6. Field Naming Inconsistencies

### Severity: MEDIUM

| # | Service | Issue |
|---|---|---|
| GAP-019 | capacity-availability | `AvailabilitySnapshot` uses `u64` Unix timestamps instead of RFC3339 UTC. |

## 7. Missing Validation

### Severity: LOW

| # | Service | Issue |
|---|---|---|
| GAP-020 | all | No service validates `schemaVersion` on incoming events. |
| GAP-021 | all | No service rejects events with unknown `eventType`. |

## 8. TravelerRef Shape Mismatches

### Severity: HIGH — Structured TravelerRef must be adopted across all consumers

| # | Service | File | Issue | Canonical Form |
|---|---|---|---|---|
| GAP-022 | offer-management | `services/offer-management/src/domain.ts` | Uses `category: PassengerCategory` (enum: `adult`, `child`, `senior`, `student`) instead of `travelerType: TravelerType` (enum: `ADULT`, `CHILD`, `STUDENT`, `SENIOR`, `INFANT`, `MILITARY`, `DISABLED`). Field name `category` differs from canonical `travelerType`. | `travelerType` field with `ADULT`/`CHILD`/`STUDENT`/`SENIOR`/`INFANT`/`MILITARY`/`DISABLED` values. |
| GAP-023 | journey-order | `services/journey-order/.../domain/TravelerRef.java` | Uses `documentType: string` (free-form) instead of `travelerType: TravelerType` enum. Missing `eligibilityRef` field. | `travelerType` enum and optional `eligibilityRef`. |

## 9. Eligibility Reference Mismatches

### Severity: HIGH — Canonical EligibilityRef must be adopted

| # | Service | File | Issue | Canonical Form |
|---|---|---|---|---|
| GAP-024 | offer-management | `services/offer-management/src/domain.ts` | Uses `eligibilitySnapshotRef?: SnapshotId` (free-form string) instead of structured `EligibilityRef`. | `EligibilityRef` with `eligibilityId`, `eligibilityType`, `evidenceHash`, `verifiedAt`. |
| GAP-025 | journey-order | `services/journey-order/.../domain/OfferSnapshotRef.java` | Carries `ruleSnapshotId: String` (free-form string) instead of structured `EligibilityRef`. Missing `eligibilityRef` in `TravelerRef`. | `EligibilityRef` in `TravelerRef`; `OfferSnapshotRef.ruleSnapshotId` is separate from eligibility. |
| GAP-026 | traveler-profile | `services/traveler-profile` | Produces `EligibilitySummary` (definition pending — no domain code on base branch yet). Must align with canonical `EligibilityRef` shape. | `EligibilityRef` with `eligibilityId`, `eligibilityType`, `eligibilitySource`, `evidenceHash`, `verifiedAt`. |

## 10. Offer → Order Reference Alignment

### Severity: HIGH — OfferQuoted downstreamReference and journey-order OfferSnapshotRef must round-trip

| # | Service | File | Issue | Canonical Form |
|---|---|---|---|---|
| GAP-027 | offer-management | `services/offer-management/src/domain.ts` | `downstreamReference` has `offerId`, `offerVersion`, `priceSnapshotRef` but **missing `ruleSnapshotRef`**. journey-order's `OfferSnapshotRef` requires `ruleSnapshotId`. | Add `ruleSnapshotRef` to `downstreamReference`. |
| GAP-028 | journey-order | `services/journey-order/.../domain/OfferSnapshotRef.java` | `OfferSnapshotRef` requires `ruleSnapshotId` but the canonical source (`OfferQuoted.downstreamReference`) does not yet provide it. | Consume `ruleSnapshotRef` from `OfferQuoted.downstreamReference` once added. |

## 11. AvailabilitySnapshot Contract Completeness

### Severity: HIGH — Field-level definition must be consumed correctly

| # | Service | File | Issue | Canonical Form |
|---|---|---|---|---|
| GAP-029 | offer-management | `services/offer-management/src/domain.ts` | `AvailabilitySnapshotReference` has `sellable: boolean` and `confidence: AvailabilityConfidence` enum but no `status` field. The canonical `AvailabilitySnapshot` uses a status vocabulary (`AVAILABLE`/`LIMITED`/`UNKNOWN`/`UNAVAILABLE`). | Add `status` field and map confidence values per cross-context mapping table in events/capacity-availability.md. |
| GAP-030 | trip-planning | `services/trip-planning/src/trip_planning/domain.py` | Availability hints use internal vocabulary (`available_hint`, `limited`, `unknown`, `unavailable`) but may not use canonical `AvailabilitySnapshot` structure. | Consume canonical `AvailabilitySnapshot` with status mapping. |

## 12. Conformance Summary

| Service | GAP Count | Severity |
|---|---|---|
| fare-pricing | 1 | HIGH |
| offer-management | 5 | HIGH |
| journey-order | 5 | HIGH |
| payment | 3 | HIGH |
| capacity-availability | 2 | MEDIUM |
| booking-orchestration | 3 | HIGH |
| shared-kernel-rust | 1 | MEDIUM |
| trip-planning | 1 | MEDIUM |
| traveler-profile | 1 | HIGH |

**Total gaps: 22** (14 HIGH, 8 MEDIUM)

All HIGH-severity gaps MUST be resolved before integration testing (联调).

## Migration Plan

### Phase 1 (Before Integration Testing)
1. GAP-001..004: Adopt `minorUnits: i64` in all Money types.
2. GAP-007..009: Add ID prefix validation.
3. GAP-010..012: Wrap all events in `EventEnvelope`.
4. GAP-022..023: Adopt structured `TravelerRef` with `travelerType` enum.
5. GAP-024..026: Adopt canonical `EligibilityRef`.
6. GAP-027..028: Align `OfferQuoted.downstreamReference` and `OfferSnapshotRef`.

### Phase 2 (Before Production)
1. GAP-006: Add ID prefix convention to capacity-availability.
2. GAP-013..014: Normalise JSON field naming and enum casing.
3. GAP-015..018: Publish missing events as cross-context `EventEnvelope`.
4. GAP-029..030: Consume canonical `AvailabilitySnapshot` structure.

### Phase 3 (Technical Debt)
1. GAP-005: Update shared-kernel to validate ID prefixes.
2. GAP-019: Use RFC3339 UTC timestamps in capacity-availability.
3. GAP-020..021: Add schema version and event type validation.
