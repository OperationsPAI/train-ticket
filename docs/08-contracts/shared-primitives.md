# Shared Primitives — Language-Neutral Field-Level Definitions

Last updated: 2026-07-04

## 1. EventEnvelope

Every cross-context event is wrapped in an `EventEnvelope`. Consumers MUST
validate the envelope before processing the payload.

| Field | Type | Required | Description |
|---|---|---|---|
| `eventId` | `EventId` | yes | Globally unique event identifier. Format: `evt-<uuid>` or `evt-<nanos>-<seq>`. |
| `eventType` | string | yes | Fully qualified event type name (e.g. `JourneyOrderCreated`). PascalCase. |
| `occurredAt` | RFC3339 UTC | yes | Wall-clock time when the event was recorded in the producer. |
| `correlationId` | `CorrelationId` | yes | Links all events in the same business transaction. Format: `corr-<uuid>`. |
| `causationId` | `CausationId` | no | ID of the command or event that caused this event. Format: `cmd-<uuid>` or `evt-<uuid>`. |
| `producer` | string | yes | Bounded context that published the event (e.g. `journey-order`). |
| `schemaVersion` | u32 | yes | Positive integer. Consumers MUST reject unknown major versions. |
| `payload` | object | yes | The event-specific data. |

**Reference implementation:** `platform/shared-kernel-rust/src/lib.rs` — `EventEnvelope` struct.

## 2. Shared ID Formats

All IDs are non-empty strings with a prefix convention. The prefix is part of the
canonical identity and MUST be preserved in serialisation.

| ID Type | Prefix | Format | Description |
|---|---|---|---|
| `PlaceId` | `plc-` | `plc-<uuid>` | Canonical place (city, station, airport, POI). |
| `TransportNodeId` | `tnd-` | `tnd-<uuid>` | Transport-meaningful node (platform, gate, terminal). |
| `ServicePlanId` | `sp-` | `sp-<uuid>` | Service plan aggregate root. |
| `ServicePatternId` | `spat-` | `spat-<uuid>` | Ordered stop sequence for a service mode. |
| `CalendarId` | `cal-` | `cal-<uuid>` | Operating calendar. |
| `TimetableId` | `tt-` | `tt-<uuid>` | Planned stop times. |
| `PlanVersionId` | `pv-` | `pv-<uuid>` | Versioned plan publication. |
| `ScheduledServiceId` | `ss-` | `ss-<uuid>` | Materialised service instance for a specific date. |
| `SegmentRef` | `seg-` | `seg-<uuid>` or `seg-<patternId>:<fromSeq>-<toSeq>` | Reference to a service segment. |
| `TravelerRef` / `TravelerId` | `tvl-` | `tvl-<uuid>` | Cross-context traveler reference (see section 2a for structured shape). |
| `EligibilityId` | `elig-` | `elig-<uuid>` | Eligibility determination reference (see section 2b for EligibilityRef shape). |
| `OfferId` | `off-` | `off-<uuid>` | Offer aggregate root. |
| `OrderId` / `JourneyOrderId` | `ord-` | `ord-<uuid>` | Journey order aggregate root. |
| `OrderItemId` | `oit-` | `oit-<uuid>` | Line item within a journey order. |
| `SegmentBookingId` | `sb-` | `sb-<uuid>` | Segment booking aggregate root. |
| `HoldId` | `hold-` | `hold-<uuid>` | Capacity hold aggregate root. |
| `PaymentIntentId` | `pi-` | `pi-<uuid>` | Payment intent aggregate root. |
| `RefundId` | `ref-` | `ref-<uuid>` | Refund aggregate root. |
| `EntitlementId` | `ent-` | `ent-<uuid>` | Ticket/entitlement aggregate root. |
| `PostSalesCaseId` | `psc-` | `psc-<uuid>` | Post-sales case aggregate root. |
| `ProviderId` | `prv-` | `prv-<uuid>` | External provider/supplier reference. |
| `RuleSetId` | `rs-` | `rs-<uuid>` | Fare rule set aggregate root. |
| `RuleVersion` | string | `<semver>` | Rule set version (e.g. `1.0.0`, `2.1.3`). |
| `EventId` | `evt-` | `evt-<uuid>` or `evt-<nanos>-<seq>` | Unique event identifier. |
| `CorrelationId` | `corr-` | `corr-<uuid>` | Business transaction correlation. |
| `CausationId` | `cmd-` or `evt-` | `cmd-<uuid>` or `evt-<uuid>` | Source command or event ID. |
| `IdempotencyKey` | `idem-` | `idem-<orderId>:<segmentRef>:<travelerRef>:<purpose>` | Idempotency key for safe retry. |
| `InventoryPoolId` | `pool-` | `pool-<scheduledServiceRef>-<seatClass>` | Inventory pool identity. |
| `CapacityUnitRef` | `cu-` | `cu-<seatLabel>` | Specific capacity unit (e.g. `cu-01A`). |
| `FareQuoteId` | `fq-` | `fq-<uuid>` | Fare quote reference. |
| `SagaId` | `saga-` | `saga-<uuid>` | Booking saga identity. |
| `DisruptionCaseId` | `dc-` | `dc-<uuid>` | Disruption case identity. |
| `TransferPlanId` | `tp-` | `tp-<uuid>` | Transfer plan identity. |
| `ProviderReference` | varies | provider-specific | External provider reference (PNR, confirmation number, ticket number). |
| `AccountId` | `acct-` | `acct-<uuid>` | Account aggregate root. |
| `SessionId` | `sess-` | `sess-<uuid>` | Session aggregate root. |
| `PreferenceId` | `pref-` | `pref-<uuid>` | Preference aggregate root. |
| `ClosureRequestId` | `clr-` | `clr-<uuid>` | Account closure saga request. |
| `AvailabilitySnapshotId` | `avs-` | `avs-<uuid>` | Availability snapshot identity. |

**Reference implementation:** `platform/shared-kernel-rust/src/lib.rs` — `PlaceRef`, `TravelerRef`, `SegmentRef`, `EventId`, `CausationId`, `CorrelationId`.

### Deviations from shared-kernel reference

The Rust shared-kernel uses `PlaceRef`, `TravelerRef`, `SegmentRef` as generic
wrapper types without prefix validation. The canonical ID formats above define
the prefix conventions that all services should adopt. See `conformance-gaps.md`
for current deviations.

## 2a. TravelerRef — Structured Cross-Context Shape

The TravelerRef is both a simple string ID (`tvl-<uuid>`) and a structured value
object when full traveler context is needed across bounded contexts.

### Simple ID form

Used where only a reference is needed (e.g. in event headers or audit trails):
`tvl-<uuid>`

### Structured form (cross-context payload)

```json
{
  "travelerId": "tvl-abc123",
  "travelerType": "ADULT",
  "maskedDocumentRef": "****1234",
  "eligibilityRef": { "eligibilityId": "elig-def456", "eligibilityType": "STUDENT", "evidenceHash": "sha256-..." }
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `travelerId` | `TravelerId` | yes | Canonical traveler string ID (`tvl-<uuid>`). |
| `travelerType` | enum | yes | Traveler category (see enum below). The field name is `travelerType`, NOT `category`. |
| `maskedDocumentRef` | string | no | Partially masked travel document number (e.g. `****1234`). MUST NOT contain full document number. |
| `eligibilityRef` | `EligibilityRef` | no | Eligibility determination reference (see section 2b). |

### TravelerType enum

| Value | Description |
|---|---|
| `ADULT` | Adult passenger (default). |
| `CHILD` | Child passenger (age-dependent fare). |
| `STUDENT` | Student passenger (eligibility-verified discount). |
| `SENIOR` | Senior passenger (age-dependent fare). |
| `INFANT` | Infant (no seat, accompanying adult). |
| `MILITARY` | Military personnel (eligibility-verified discount). |
| `DISABLED` | Passenger with disability (eligibility-verified assistance). |

### Deviations from canonical form

- **offer-management (TypeScript):** Uses `category` field (type `PassengerCategory` enum with values `adult`, `child`, `senior`, `student`) instead of `travelerType`. See conformance-gaps.md GAP-022.
- **journey-order (Java):** `TravelerRef` record has `travelerId`, `documentType`, `maskedDocumentNo` but uses `documentType` (free-form string) rather than the canonical `travelerType` enum. See conformance-gaps.md GAP-023.

## 2b. EligibilityRef — Canonical Eligibility Reference

Unified reference for eligibility determinations produced by traveler-profile and
consumed by offer-management, fare-pricing, and journey-order.

```json
{
  "eligibilityId": "elig-def456",
  "eligibilityType": "STUDENT",
  "eligibilitySource": "traveler-profile",
  "evidenceHash": "sha256-abc123...",
  "verifiedAt": "2026-07-03T10:00:00Z"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `eligibilityId` | `EligibilityId` | yes | Canonical eligibility ID (`elig-<uuid>`). |
| `eligibilityType` | enum | yes | Type of eligibility (see EligibilityType enum). |
| `eligibilitySource` | string | yes | Bounded context that determined eligibility (e.g. `traveler-profile`, `fare-pricing`). |
| `evidenceHash` | string | no | Hash of the evidence document used for verification (SHA-256 hex). |
| `verifiedAt` | RFC3339 UTC | no | When the eligibility was verified. |

### EligibilityType enum

| Value | Description |
|---|---|
| `STUDENT` | Student discount eligibility. |
| `SENIOR` | Senior discount eligibility. |
| `MILITARY` | Military discount eligibility. |
| `DISABLED` | Disability assistance eligibility. |
| `LOYALTY` | Loyalty programme tier-based eligibility. |
| `CORPORATE` | Corporate travel agreement eligibility. |
| `PROMOTIONAL` | Promotional campaign eligibility. |
| `GENERAL` | No special eligibility (default). |

### Deviations from canonical form

- **offer-management (TypeScript):** Uses `eligibilitySnapshotRef?: SnapshotId` (free-form string) instead of the structured `EligibilityRef`. See conformance-gaps.md GAP-024.
- **journey-order (Java):** `OfferSnapshotRef` carries `ruleSnapshotId` (free-form string) instead of the structured `EligibilityRef`. See conformance-gaps.md GAP-025.
- **traveler-profile (Java):** Produces `EligibilitySummary` with similar fields but uses slightly different naming. The canonical shape above is the contract target. See conformance-gaps.md GAP-026.

## 3. Money

Money is **never** represented as a floating-point number. The canonical shape is:

```json
{
  "currency": "CNY",
  "minorUnits": 12345
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `currency` | string | yes | ISO-4217 three-letter uppercase code (e.g. `CNY`, `USD`, `EUR`, `JPY`). |
| `minorUnits` | integer | yes | Amount in the smallest currency unit (cents, fen, etc.). Always a whole number. |

**Rules:**
- `currency` MUST be exactly three uppercase ASCII letters.
- `minorUnits` MUST be a signed integer. Negative values represent amounts owed to the customer.
- Services MUST NOT use `float`, `double`, or `Decimal` in cross-context serialisation.
- For display, divide by 10^(currency exponent). For CNY: `minorUnits / 100`.

**Reference implementation:** `platform/shared-kernel-rust/src/lib.rs` — `Money` struct.

### Deviations

- **fare-pricing (Python):** Uses `Decimal` amounts with implicit two-decimal-place semantics rather than `minorUnits: i64`. The canonical form is `minorUnits`. Migration tracked in `conformance-gaps.md`.
- **offer-management (TypeScript):** Uses `amount: number` in the Money type. This is a deviation from the canonical `minorUnits: integer`. Migration tracked in `conformance-gaps.md`.
- **journey-order (Java):** Uses `Money` class with `BigDecimal`. This is a deviation from the canonical `minorUnits: i64`. Migration tracked in `conformance-gaps.md`.
- **payment (Java):** Uses `Money` class with `BigDecimal`. Deviation tracked in `conformance-gaps.md`.

## 4. TimeWindow

A closed-open interval `[start, end)`.

```json
{
  "start": "2026-07-03T10:00:00Z",
  "end": "2026-07-03T10:30:00Z"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `start` | RFC3339 UTC | yes | Start of the window (inclusive). |
| `end` | RFC3339 UTC | yes | End of the window (exclusive). MUST be after `start`. |

**Reference implementation:** `platform/shared-kernel-rust/src/lib.rs` — `TimeWindow` struct.

## 5. Timestamp Convention

All date-time fields in cross-context messages MUST be:

- UTC timezone
- RFC3339 / ISO-8601 format
- Trailing `Z` (not `+00:00`, not numeric offset)
- Microsecond or millisecond precision preferred

| Valid | Invalid |
|---|---|
| `2026-07-03T10:30:00Z` | `2026-07-03T10:30:00+08:00` |
| `2026-07-03T10:30:00.123Z` | `2026-07-03T10:30:00` |
| `2026-07-03T10:30:00.123456Z` | `2026-07-03 10:30:00` |

The shared-kernel `UnixMillis(u64)` represents timestamps as milliseconds since
UNIX epoch for internal processing. Cross-context serialisation MUST use RFC3339.

## 6. Enums

Enum values are serialised as SCREAMING_SNAKE_CASE strings in JSON.

### Common status enums used across contexts:

**OrderLifecycleState:**
| Value | Description |
|---|---|
| `PENDING_CONFIRMATION` | Order created, awaiting booking/capacity acceptance. |
| `PENDING_PAYMENT` | Awaiting payment capture. |
| `CONFIRMING` | Payment captured, awaiting entitlement summary. |
| `CONFIRMED` | All confirmation conditions satisfied. |
| `IN_TRAVEL` | At least one segment has started. |
| `POST_SALES_ADJUSTED` | Order modified by post-sales action. |
| `CANCELLED` | Order cancelled. |
| `COMPLETED` | All segments completed. |

**SegmentBookingStatus:**
| Value | Description |
|---|---|
| `REQUESTED` | Reservation request submitted. |
| `HOLDING` | Capacity hold in progress. |
| `CONFIRMED` | Reservation confirmed (internal or provider). |
| `TICKETED` | Entitlement issued for this segment. |
| `CANCEL_REQUESTED` | Cancellation requested. |
| `CANCELLED` | Cancellation completed. |
| `FAILED` | Reservation failed. |

**PaymentIntentStatus:**
| Value | Description |
|---|---|
| `CREATED` | Payment intent created. |
| `AUTHORIZED` | Amount authorised by channel. |
| `CAPTURED` | Amount captured. |
| `FAILED` | Payment failed. |
| `CANCELLED` | Payment cancelled. |
| `EXPIRED` | Payment expired. |

**EntitlementStatus:**
| Value | Description |
|---|---|
| `PENDING_ISSUE` | Entitlement created but not yet issued. |
| `ISSUED` | Entitlement issued (ticket generated). |
| `CHECKED_IN` | Boarding check-in completed. |
| `BOARDED` | Passenger boarded. |
| `USED` | Segment completed. |
| `VOIDED` | Entitlement voided (refunded/cancelled). |
| `SUSPENDED` | Entitlement suspended (dispute/risk). |

**HoldState:**
| Value | Description |
|---|---|
| `REQUESTED` | Hold requested. |
| `HELD` | Hold active. |
| `CONFIRMED` | Hold confirmed (payment conditions met). |
| `RELEASED` | Hold released. |
| `EXPIRED` | Hold TTL elapsed. |
| `FAILED` | Hold failed (conflict). |

**BookingSagaStatus:**
| Value | Description |
|---|---|
| `PLANNED` | Saga created but not started. |
| `RESERVING` | Segments being reserved. |
| `COMPLETED` | All steps completed successfully. |
| `FAILED` | Saga failed. |
| `MANUAL_REVIEW` | Manual intervention required. |

**PostSalesCaseStatus:**
| Value | Description |
|---|---|
| `REQUESTED` | Post-sales case opened. |
| `EVALUATED` | Rule evaluation completed. |
| `APPROVED` | Case approved for execution. |
| `APPLIED` | Post-sales result applied. |
| `FAILED` | Post-sales execution failed. |

**TravelerType:**
| Value | Description |
|---|---|
| `ADULT` | Adult passenger (default). |
| `CHILD` | Child passenger (age-dependent fare). |
| `STUDENT` | Student passenger (eligibility-verified discount). |
| `SENIOR` | Senior passenger (age-dependent fare). |
| `INFANT` | Infant (no seat, accompanying adult). |
| `MILITARY` | Military personnel (eligibility-verified discount). |
| `DISABLED` | Passenger with disability (eligibility-verified assistance). |

**EligibilityType:**
| Value | Description |
|---|---|
| `STUDENT` | Student discount eligibility. |
| `SENIOR` | Senior discount eligibility. |
| `MILITARY` | Military discount eligibility. |
| `DISABLED` | Disability assistance eligibility. |
| `LOYALTY` | Loyalty programme tier-based eligibility. |
| `CORPORATE` | Corporate travel agreement eligibility. |
| `PROMOTIONAL` | Promotional campaign eligibility. |
| `GENERAL` | No special eligibility (default). |

**AvailabilityStatus (cross-context mapping):**
| Value | trip-planning hint | offer-management confidence | Description |
|---|---|---|---|
| `AVAILABLE` | `available_hint` | `confirmed-snapshot` | Sufficient sellable units. |
| `LIMITED` | `limited` | `low` | Few units remain. |
| `UNKNOWN` | `unknown` | `estimated` | Snapshot stale/incomplete. |
| `UNAVAILABLE` | `unavailable` | N/A | No sellable units. |

## 7. ProviderReference

External provider/supplier references are encapsulated in a value object:

| Field | Type | Required | Description |
|---|---|---|---|
| `providerId` | `ProviderId` | yes | Platform-internal provider identity. |
| `confirmationNo` | string | yes | Provider's reservation/confirmation identifier (PNR, ticket number, etc.). |
| `rawStatus` | string | no | Provider's raw status code (for audit/debugging only). |
| `mappedStatus` | string | yes | Platform-mapped status (see `docs/01-ddd-high-level/acl-provider-contracts.md`). |
| `mappedAt` | RFC3339 UTC | yes | When the status was mapped. |

**Reference implementation:** `services/provider-integration/internal/domain/acl.go`.

## 8. CapacityUnitRef

Identifies a specific sellable unit within an inventory pool.

```json
{
  "capacityUnitRef": "cu-01A"
}
```

For trains, this is typically a specific seat (carriage + seat number).
The format is `cu-<carriage><seatLabel>`.

**Reference implementation:** `services/capacity-availability/src/lib.rs` — `CapacityUnitRef`.

## 9. StationInterval

Half-open interval `[fromSeq, toSeq)` representing a range of stops on a
service pattern.

```json
{
  "fromSeq": 1,
  "toSeq": 3
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `fromSeq` | u32 | yes | Origin stop sequence (inclusive). |
| `toSeq` | u32 | yes | Destination stop sequence (exclusive). MUST be > `fromSeq`. |

Two intervals overlap if `a.fromSeq < b.toSeq && b.fromSeq < a.toSeq`.

**Reference implementation:** `services/capacity-availability/src/lib.rs` — `StationInterval`.

## 10. AvailabilitySnapshot

Non-authoritative snapshot of sellable units for a station interval.

```json
{
  "inventoryPoolId": "pool-G123-2026-07-03-first-class",
  "requestedInterval": { "fromSeq": 1, "toSeq": 3 },
  "sourceVersion": 7,
  "generatedAt": "2026-07-03T10:00:00Z",
  "validUntil": "2026-07-03T10:05:00Z",
  "totalUnits": 100,
  "availableCount": 45,
  "status": "AVAILABLE",
  "explanations": ["INVENTORY_AVAILABLE"]
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `inventoryPoolId` | `InventoryPoolId` | yes | The pool this snapshot applies to. |
| `requestedInterval` | `StationInterval` | yes | The stop range queried. |
| `sourceVersion` | u64 | yes | Pool version at snapshot time. |
| `generatedAt` | RFC3339 UTC | yes | When the snapshot was computed. |
| `validUntil` | RFC3339 UTC | yes | Snapshot expiry. |
| `totalUnits` | u32 | yes | Total sellable units in the pool for this interval. |
| `availableCount` | u32 | yes | Estimated available units. |
| `status` | enum | yes | `AVAILABLE`, `LOW_AVAILABILITY`, or `SOLD_OUT`. |
| `explanations` | string[] | no | Human-readable or code-based explanations. |

**Reference implementation:** `services/capacity-availability/src/lib.rs` — `AvailabilitySnapshot`.

## 11. Money (minorUnits) vs Decimal Migration Note

The platform shared-kernel defines `Money { amount_minor: i64, currency: CurrencyCode }`.
Services that use `Decimal` or `float` for cross-context money values MUST migrate
to the `minorUnits: i64` convention before integration testing.

Affected services and their current representation:

| Service | Current Type | Canonical Form | Migration Priority |
|---|---|---|---|
| fare-pricing | `Decimal` | `minorUnits: i64` | HIGH |
| offer-management | `amount: number` (float) | `minorUnits: i64` | HIGH |
| journey-order | `BigDecimal` | `minorUnits: i64` | HIGH |
| payment | `BigDecimal` | `minorUnits: i64` | HIGH |

## 12. Shared-Kernel Rust Reference

The authoritative reference implementation for shared primitives lives at:
`platform/shared-kernel-rust/src/lib.rs`

Key primitives defined:
- `PlaceRef`, `TravelerRef`, `SegmentRef` — generic ID wrappers
- `CurrencyCode` — ISO-4217 validation
- `Money` — minorUnits + currency
- `UnixMillis` — epoch millisecond timestamp
- `TimeWindow` — validated interval
- `EventEnvelope` — event wrapper with eventId, eventType, schemaVersion, occurredAt, correlationId, causationId
- `ContractError` — validation error enum
- `RequestContext` — HTTP request context with requestId and correlationId
