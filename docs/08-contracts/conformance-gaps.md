# Conformance Gaps — Field-Level Mismatches

Last updated: 2026-07-15

This document lists concrete mismatches between the contract specification
(`docs/08-contracts/`) and the current service implementations. Each gap MUST
be resolved before integration testing (联调).

> **Reconciled 2026-07-15:** Of the 14 gaps that were still unmarked after the
> 2026-07-04 pre-integration audit, 11 are now resolved (GAP-006, 007, 008, 009,
> 012, 015, 016, 017, 018, 020, 026), 1 is N/A because the cited code was retired
> (GAP-013 — the Python trip-planning is retired; the deployed image builds the
> Rust `services/trip-planning-rs`), and 2 remain genuinely open: **GAP-005**
> (shared-kernel-rust ID wrappers still validate non-empty only, no prefix check)
> and **GAP-021** (services deliberately *ignore* unknown `eventType` rather than
> rejecting it — a design decision to revisit, not yet a rejection path). The 19
> gaps marked resolved in the original audit are unchanged.

## 1. Money Representation

### Severity: HIGH — All services must use `minorUnits: i64`

| # | Service | File | Issue | Canonical Form |
|---|---|---|---|---|
| GAP-001 | fare-pricing | `services/fare-pricing/src/fare_pricing/domain.py` | `Money` uses `Decimal` amount with implicit two-decimal-place semantics. | ✅ RESOLVED: added `amount_minor` property and `from_minor` factory. |
| GAP-002 | offer-management | `services/offer-management/src/domain.ts` | `Money` type uses `amount: number` (float). | ✅ RESOLVED: replaced `amount` with `amountMinor: integer`. |
| GAP-003 | journey-order | `services/journey-order/.../domain/Money.java` | Uses `BigDecimal` amount. | ✅ RESOLVED: added `toMinorUnits()` and `fromMinorUnits()`. |
| GAP-004 | post-sales | `services/post-sales/.../domain/Money.java` | Uses `BigDecimal` amount without minor-unit boundary. | ✅ RESOLVED: added `fromMinorUnits(long, String)` and `toMinorUnits()` matching journey-order pattern. |

## 2. ID Prefix Conventions

### Severity: HIGH — Services must adopt canonical ID prefixes

| # | Service | File | Issue | Canonical Prefix |
|---|---|---|---|---|
| GAP-005 | shared-kernel-rust | `platform/shared-kernel-rust/src/lib.rs` | Generic wrappers without prefix validation. | ⚠️ OPEN: the `id_type!` macro (`lib.rs:350`) still validates only non-empty; `PlaceRef`/`TravelerRef`/`SegmentRef` accept any string with no `plc-`/`tvl-`/`seg-` prefix check (Phase 3 tech debt). |
| GAP-006 | capacity-availability | `services/capacity-availability/src/lib.rs` | Free-form strings without prefix convention. | ✅ RESOLVED: hold IDs minted as `format!("hold-{}", uuid::Uuid::now_v7())` (`services/capacity-availability/src/application.rs:319`); snapshots as `avs-`. |
| GAP-007 | booking-orchestration | `SegmentBooking.java` | `UUID.randomUUID()` without prefix. | ✅ RESOLVED: aggregate no longer mints its own ID — it validates a supplied `segmentBookingId` via `requireText` (`SegmentBooking.java:39`), and callers pass canonical `sb-<uuidv7>` (e.g. `deploy/e2e/02-purchase.sh:73`, controller contract). |
| GAP-008 | payment | `PaymentIntent.java` | `UUID.randomUUID()` without prefix. | ✅ RESOLVED: `new PaymentIntent("pi-" + UUID.randomUUID(), …)` (`services/payment/.../domain/PaymentIntent.java:181`). |
| GAP-009 | journey-order | `JourneyOrder.java` | `UUID.randomUUID()` without prefix. | ✅ RESOLVED: `"ord-" + UuidV7.generate()` (`services/journey-order/.../domain/JourneyOrder.java:75`). |

## 3. Event Envelope Compliance

### Severity: HIGH — All cross-context events must use EventEnvelope

| # | Service | File | Issue | Required |
|---|---|---|---|---|
| GAP-010 | journey-order | `JourneyOrderCreated.java` | Events use `EventMetadata`, not `EventEnvelope`. | ✅ RESOLVED: replaced `EventMetadata` with canonical `EventEnvelope`. |
| GAP-011 | payment | `PaymentIntent.java` | Events use `PaymentEvent` with `EventMetadata`. | ✅ RESOLVED: replaced `EventMetadata` with canonical `EventEnvelope`. |
| GAP-012 | booking-orchestration | `BookingSaga.java` | Events use `DomainEvent` base. | ✅ RESOLVED: `publishEvents()` maps every `DomainEvent` to a `ContractEvent` and emits a canonical `EventEnvelope` (`services/booking-orchestration/.../application/BookingOrchestrationService.java:668-682`). |

| GAP-010-A | entitlement-ticketing | `src/lib.rs` | Domain events are bare structs without EventEnvelope. | ✅ RESOLVED: added `EventEnvelope` type with `wrap_domain_event()` helper per shared-primitives.md. |
| GAP-010-B | supplier-catalog | `internal/domain/events.go` | Domain events are bare structs without EventEnvelope. | ✅ RESOLVED: added `EventEnvelope` type with `WrapDomainEvent()` helper per shared-primitives.md. |
| GAP-010-C | service-plan | `internal/domain/service_plan.go` | Domain events are bare structs without EventEnvelope. | ✅ RESOLVED: added `EventEnvelope` type with `WrapDomainEvent()` helper per shared-primitives.md. |
## 4. Serialisation Conventions

### Severity: MEDIUM — JSON field naming and enum casing

| # | Service | File | Issue | Required |
|---|---|---|---|---|
| GAP-013 | trip-planning | `domain.py` | Accepts both camelCase and snake_case. | ✅ N/A (retired): the Python `services/trip-planning` is retired; the deployed `trip-planning` image builds the Rust `services/trip-planning-rs` (`deploy/docker/trip-planning/Dockerfile:6-11`), so `domain.py` no longer ships. |
| GAP-014 | offer-management | `domain.ts` | Enums are PascalCase (`"Quoted"`). | ✅ RESOLVED: added `mapAvailabilityConfidence()` mapping function per docs/08-contracts/events/capacity-availability.md mapping table. Internal enums remain PascalCase; boundary converts to contract canonical SCREAMING_SNAKE. |

## 5. Missing Event Publications

### Severity: MEDIUM

| # | Context | Event | Status |
|---|---|---|---|
| GAP-015 | booking-orchestration | `BookingSagaStarted` | ✅ RESOLVED: published as cross-context `EventEnvelope` with `eventType` `BookingSagaStarted` (`services/booking-orchestration/.../application/BookingOrchestrationService.java:688-690`, via `publishEvents`). |
| GAP-016 | payment | `PaymentIntentCreated` | ✅ RESOLVED: carries an `EventEnvelope` (`services/payment/.../domain/PaymentIntentCreated.java:5`, minted at `PaymentIntent.java:182`), mapped in `EventEnvelopeMapper.java:45` and published via `PaymentCommandService`. |
| GAP-017 | payment | `RefundSettled` | ✅ RESOLVED: carries an `EventEnvelope` (`services/payment/.../domain/RefundSettled.java:5`, minted at `Refund.java:216`), mapped in `EventEnvelopeMapper.java:119` and published via `PaymentCommandService`. |
| GAP-018 | capacity-availability | `AvailabilitySnapshot` | ✅ RESOLVED: emitted as a standalone `CapacitySnapshotUpdated` domain event carrying the snapshot (`services/capacity-availability/src/domain.rs:1111`), published as an `EventEnvelope` with `eventType` `CapacitySnapshotUpdated` (`application.rs:814-815`). |

## 6. Field Naming Inconsistencies

### Severity: MEDIUM

| # | Service | Issue |
|---|---|---|
| GAP-019 | capacity-availability | `AvailabilitySnapshot` uses `u64` Unix timestamps instead of RFC3339 UTC. | ✅ RESOLVED: added `unix_millis_to_rfc3339()` and `rfc3339_to_unix_millis()` conversion helpers at serialization boundary. Internal UnixMillis preserved for performance; all wire-format timestamps use RFC3339 UTC. |

## 7. Missing Validation

### Severity: LOW

| # | Service | Issue |
|---|---|---|
| GAP-020 | all | ✅ RESOLVED: `schemaVersion` is now validated at the shared envelope boundary — go-kit rejects an unsupported version (`platform/go-kit/messaging/envelope.go:93`), java-kit rejects non-positive (`platform/java-kit/.../EventEnvelope.java:49`), and TS consumers validate it (`services/notification/.../notification-service.ts:828`). |
| GAP-021 | all | ⚠️ OPEN: inbound handlers deliberately *ignore* unknown `eventType` (safe-ignore pub/sub pattern, e.g. `services/capacity-availability/src/application.rs:42` `_ => HandlerResult::Success`; `journey-order` `default -> new Success()`); no service *rejects* it. The requested canonical (reject) was not adopted — a design decision to revisit rather than a rejection path yet built. |

## 8. TravelerRef Shape Mismatches

### Severity: HIGH — Structured TravelerRef must be adopted across all consumers

| # | Service | File | Issue | Canonical Form |
|---|---|---|---|---|
| GAP-022 | offer-management | `services/offer-management/src/domain.ts` | Uses `category: PassengerCategory` (enum: `adult`, `child`, `senior`, `student`) instead of `travelerType: TravelerType` (enum: `ADULT`, `CHILD`, `STUDENT`, `SENIOR`, `INFANT`, `MILITARY`, `DISABLED`). Field name `category` differs from canonical `travelerType`. | ✅ RESOLVED: replaced `PassengerCategory` with `TravelerType` enum and `category` with `travelerType`. |
| GAP-023 | journey-order | `services/journey-order/.../domain/TravelerRef.java` | Uses `documentType: string` (free-form) instead of `travelerType: TravelerType` enum. Missing `eligibilityRef` field. | ✅ RESOLVED: added `travelerType` enum and `EligibilityRef`. |

## 9. Eligibility Reference Mismatches

### Severity: HIGH — Canonical EligibilityRef must be adopted

| # | Service | File | Issue | Canonical Form |
|---|---|---|---|---|
| GAP-024 | offer-management | `services/offer-management/src/domain.ts` | Uses `eligibilitySnapshotRef?: SnapshotId` (free-form string) instead of structured `EligibilityRef`. | ✅ RESOLVED: replaced with structured `eligibilityRef` matching contract. |
| GAP-025 | journey-order | `services/journey-order/.../domain/OfferSnapshotRef.java` | Carries `ruleSnapshotId: String` (free-form string) instead of structured `EligibilityRef`. Missing `eligibilityRef` in `TravelerRef`. | ✅ RESOLVED: added `EligibilityRef` record and `TravelerRef.eligibilityRef` field. |
| GAP-026 | traveler-profile | `services/traveler-profile` | Produces `EligibilitySummary` (definition pending — no domain code on base branch yet). Must align with canonical `EligibilityRef` shape. | ✅ RESOLVED: domain code now ships — `EligibilitySummary` carries the canonical identity fields `eligibilityId`, `eligibilityType`, `eligibilitySource`, `evidenceHash` (`services/traveler-profile/.../domain/EligibilitySummary.java:7-10`), and the service emits a canonical `elig-`-prefixed `eligibilityRef` (`TravelerProfileService.java:199`). Validity is modeled as `validFrom`/`validUntil` rather than a single `verifiedAt`. |

## 10. Offer → Order Reference Alignment

### Severity: HIGH — OfferQuoted downstreamReference and journey-order OfferSnapshotRef must round-trip

| # | Service | File | Issue | Canonical Form |
|---|---|---|---|---|
| GAP-027 | offer-management | `services/offer-management/src/domain.ts` | `downstreamReference` has `offerId`, `offerVersion`, `priceSnapshotRef` but **missing `ruleSnapshotRef`**. journey-order's `OfferSnapshotRef` requires `ruleSnapshotId`. | ✅ RESOLVED: added `ruleSnapshotRef` to `downstreamReference`. |
| GAP-028 | journey-order | `services/journey-order/.../domain/OfferSnapshotRef.java` | `OfferSnapshotRef` requires `ruleSnapshotId` but the canonical source (`OfferQuoted.downstreamReference`) does not yet provide it. | ✅ RESOLVED: `OfferSnapshotRef` now consumes `ruleSnapshotId` and `priceSnapshotRef`. |

## 11. AvailabilitySnapshot Contract Completeness

### Severity: HIGH — Field-level definition must be consumed correctly

| # | Service | File | Issue | Canonical Form |
|---|---|---|---|---|
| GAP-029 | offer-management | `services/offer-management/src/domain.ts` | `AvailabilitySnapshotReference` has `sellable: boolean` and `confidence: AvailabilityConfidence` enum but no `status` field. | ✅ RESOLVED: added `status` field with canonical vocabulary. |
| GAP-030 | trip-planning | `services/trip-planning/src/trip_planning/domain.py` | Availability hints use internal vocabulary (`available_hint`, `limited`, `unknown`, `unavailable`) but may not use canonical `AvailabilitySnapshot` structure. | ✅ RESOLVED: status values mapped to canonical contract vocabulary (`AVAILABLE`/`LIMITED`/`UNKNOWN`/`UNAVAILABLE`). |

## 12. Conformance Summary

| Service | GAP Count | Severity |
|---|---|---|
| fare-pricing | 1 | HIGH |
| offer-management | 5 | HIGH |
| journey-order | 5 | HIGH |
| payment | 3 | HIGH |
| capacity-availability | 1 | MEDIUM |
| booking-orchestration | 3 | HIGH |
| shared-kernel-rust | 1 | MEDIUM |
| trip-planning | 1 | MEDIUM |
| traveler-profile | 1 | HIGH |

**Total gaps: 22 (3 new GAP-010 sub-items resolved inline)** (14 HIGH, 8 MEDIUM)

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
