# Offer Management — Events & Commands

Last updated: 2026-07-04

## Published Events

### OfferQuoted

| Field | Description |
|---|---|
| **Producer** | offer-management |
| **Consumers** | journey-order |
| **Trigger** | `QuoteOffer` command processed with valid itinerary, price snapshot, and availability snapshot. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `offerId` | `OfferId` | yes | Canonical offer ID (`off-<uuid>`). |
| `offerVersion` | u32 | yes | Monotonically increasing version. |
| `quoteRequestId` | string | yes | Client-generated request correlation. |
| `accountId` | string | yes | Account that requested the offer. |
| `channelId` | string | yes | Sales channel. |
| `itineraryId` | string | yes | Reference to the Trip Planning itinerary. |
| `itineraryVersion` | string | yes | Version of the itinerary. |
| `travelerSetHash` | string | yes | Hash of the traveler set used for pricing. |
| `availabilitySnapshotRefs` | string[] | yes | References to Capacity & Availability snapshots. |
| `priceSnapshotRef` | string | yes | Reference to the frozen price snapshot. |
| `fareQuoteRefs` | string[] | yes | References to Fare & Pricing quotes. |
| `ruleSnapshotRefs` | string[] | yes | References to fare rule snapshots. |
| `total` | `Money` | yes | Total offer price. |
| `expiresAt` | RFC3339 UTC | yes | Offer validity expiry. |
| `priceGuaranteeLevel` | enum | yes | `FIXED_UNTIL_EXPIRY`, `ESTIMATED_ONLY`, or `PROVIDER_FINAL_CONFIRM_REQUIRED`. |
| `downstreamReference` | object | yes | Minimal reference for order creation. |

**downstreamReference:**

| Field | Type | Required | Description |
|---|---|---|---|
| `offerId` | `OfferId` | yes | Canonical offer ID. |
| `offerVersion` | u32 | yes | Version at quote time. |
| `priceSnapshotRef` | string | yes | Immutable price snapshot reference. |
| `ruleSnapshotRef` | string | yes | Immutable rule snapshot reference. This is the canonical reference that journey-order maps into its `OfferSnapshotRef.ruleSnapshotId` field. |

**Idempotency/Ordering Notes:**
- Idempotent on `(offerId, offerVersion)`. Same combination produces same event.
- `OfferQuoted` is an immutable fact. Once published, the price snapshot and rule snapshot cannot change.

#### Round-trip mapping to journey-order's OfferSnapshotRef

| OfferQuoted.downstreamReference field | journey-order.OfferSnapshotRef field |
|---|---|
| `offerId` | `offerId` |
| `offerVersion` | `offerVersion` |
| `priceSnapshotRef` | `priceSnapshotRef` |
| `ruleSnapshotRef` | `ruleSnapshotId` |

The journey-order context MUST construct its `OfferSnapshotRef` by copying these
four fields directly. No transformation or derivation is required.

### OfferExpired

| Field | Description |
|---|---|
| **Producer** | offer-management |
| **Consumers** | journey-order |
| **Trigger** | Offer validity window elapsed, or explicit `ExpireOffer` command. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `offerId` | `OfferId` | yes | Expired offer ID. |
| `offerVersion` | u32 | yes | Version at expiry. |
| `expiredAt` | RFC3339 UTC | yes | When the offer expired. |
| `previousStatus` | enum | yes | `QUOTED` or `ACCEPTED`. |
| `reason` | enum | yes | `VALIDITY_WINDOW_ELAPSED` or `EXPLICIT_EXPIRE_COMMAND`. |

## Accepted Commands

### QuoteOffer

| Field | Description |
|---|---|
| **Sender** | API gateway / UI |
| **Payload** | `QuoteOfferCommand` |

**QuoteOfferCommand:**

| Field | Type | Required | Description |
|---|---|---|---|
| `offerId` | `OfferId` | yes | Client-generated or server-generated offer ID. |
| `quoteRequestId` | string | yes | Client request correlation. |
| `accountId` | string | yes | Requesting account. |
| `channelId` | string | yes | Sales channel. |
| `itinerary` | `ItineraryReference` | yes | Reference to a Trip Planning itinerary. |
| `passengerMix` | `PassengerMix` | yes | Traveler set with categories. |
| `validityWindow` | `TimeWindow` | yes | Offer validity period. |
| `items` | `OfferItem[]` | yes | Segment/service items with price and availability snapshots. |
| `priceSnapshot` | `PriceSnapshot` | yes | Frozen price breakdown. |
| `riskDisclosures` | `RiskDisclosure[]` | no | Disclosures that must be accepted. |
| `quotedAt` | RFC3339 UTC | yes | Timestamp of the quote. |

## Cross-Context Dependencies

| Upstream Context | Consumed Data | Purpose |
|---|---|---|
| trip-planning | `ItineraryProposed` event, `ItineraryReference` | Base itinerary for offer. |
| fare-pricing | `FareQuote`, `RuleSnapshot` | Price and rule snapshot. |
| capacity-availability | `AvailabilitySnapshot` | Sellable availability reference. |
| traveler-profile | `TravelerRef`, eligibility data | Passenger mix validation. |
