# Ancillary Service — HTTP API

Last updated: 2026-07-09

## Overview

Ancillary Service owns the commercial and fulfillment lifecycle for non-primary
transport services such as insurance, meals, baggage/consign, seat selection,
transfer pickup, lounge, fast track, and bundles. This contract is scoped to the
ADR-0002 third activation wave and is bounded by
`docs/02-domains/ancillary-service.md`.

Activation-wave rulings:

- This wave activates exactly three aggregate roots:
  `AncillaryCatalogItem`, `AncillaryOffer`, and `AncillaryOrderItem`.
  `ServiceFulfillmentRecord` is not a separate aggregate on the wire; fulfillment
  records are facts embedded under `AncillaryOrderItem` and published as
  fulfillment-fact events.
- Pricing consults Fare & Pricing dynamic price rules using the normative
  fare-pricing input hash and rule snapshot contract. Each catalog item still
  carries a `price` Money value used as the fallback when Fare & Pricing is
  unavailable or no applicable rule set is returned. Fee assessments are carried
  on quoted offers and selected order items.
- Ancillary order items reference `journeyOrderId`, `travelerRef`, and optional
  `segmentRef`; this contract does not change Journey Order HTTP or event
  contracts. Payment integration is deferred; the resource carries informational
  `payableAmount` and `refundableAmount` only.
- Eligibility is the minimum rule set for this wave: primary-ticket status via a
  read-only entitlement lookup or caller-supplied `entitlementRef`, purchase time
  window before departure, and mandatory `segmentRef` for segment-bound services.
  Traveler age/documents, supplier capacity, fare-rule eligibility, provider
  availability, and other dimensions are deferred.
- All state-changing HTTP endpoints require `Idempotency-Key` and follow the
  standard replay/reuse behavior in `docs/08-contracts/api/README.md`.
- Finance Settlement, Notification, and Post Sales consumers are contract-surface
  touchpoints only; concrete consumer behavior is deferred to later waves.
- Ancillary Service consumes `JourneyOrderCancelled` from `events:journey-order`
  in this wave and automatically cancels associated non-terminal order items.

Field shapes reference `docs/08-contracts/shared-primitives.md` for IDs,
timestamps, event envelope fields, pagination conventions, and `Money`
(`{currency, minorUnits}`). All timestamps are RFC3339 UTC. JSON fields are
camelCase and enum values are SCREAMING_SNAKE_CASE.

## Common enums

| Enum | Values |
|---|---|
| `serviceType` | `INSURANCE`, `MEAL`, `BAGGAGE`, `CONSIGN`, `SEAT_SELECTION`, `TRANSFER_PICKUP`, `LOUNGE`, `FAST_TRACK`, `BUNDLE` |
| `attachmentScope` | `JOURNEY`, `SEGMENT`, `TRAVELER`, `ENTITLEMENT`, `PLACE`, `TRANSFER` |
| `catalogStatus` | `DRAFT`, `PUBLISHED`, `SUSPENDED`, `SUPERSEDED`, `EXPIRED` |
| `offerStatus` | `DRAFTING`, `QUOTED`, `SELECTED`, `EXPIRED`, `INELIGIBLE`, `FAILED` |
| `orderItemStatus` | `SELECTED`, `PENDING_CONFIRMATION`, `CONFIRMED`, `FULFILLMENT_READY`, `FULFILLED`, `FAILED`, `CANCELLED`, `REFUND_PENDING`, `REFUNDED` |
| `factType` | `MEAL_ISSUED`, `BAGGAGE_CHECKED`, `CONSIGN_ACCEPTED`, `CONSIGN_DELIVERED`, `SEAT_ASSIGNED`, `LOUNGE_REDEEMED`, `FAST_TRACK_USED`, `PICKUP_COMPLETED`, `INSURANCE_ACTIVATED`, `INSURANCE_VOIDED`, `SERVICE_VOUCHER_ISSUED`, `SERVICE_VOUCHER_REDEEMED`, `PROVIDER_FULFILLMENT_FAILED` |
| `eligibilityStatus` | `ELIGIBLE`, `INELIGIBLE`, `UNKNOWN` |
| `primaryTicketStatus` | `ISSUED`, `CONFIRMED`, `PENDING`, `CANCELLED`, `VOIDED`, `UNKNOWN` |
| `refundRecommendation` | `FULL_REFUND`, `PARTIAL_REFUND`, `NO_REFUND`, `MANUAL_REVIEW` |

## State machines

### AncillaryCatalogItem status

| Status | Meaning | Allowed next statuses |
|---|---|---|
| `DRAFT` | Operations are configuring the catalog item. | `PUBLISHED`, `SUSPENDED` |
| `PUBLISHED` | Item is eligible for quoting. | `SUSPENDED`, `SUPERSEDED`, `EXPIRED` |
| `SUSPENDED` | Sales are paused for supplier, compliance, inventory, or operations reasons. | `PUBLISHED`, `SUPERSEDED` |
| `SUPERSEDED` | A newer catalog item version replaces this version. | Terminal |
| `EXPIRED` | Sale or service validity elapsed. | Terminal |

Published items MUST NOT be edited in place for price, eligibility rule, or
fulfillment-rule changes; publish a superseding version instead.

### AncillaryOffer status

The wire offer status follows the domain document status table:

| Status | Meaning | Allowed next statuses |
|---|---|---|
| `DRAFTING` | Eligibility, static catalog price, and availability inputs are being collected. | `QUOTED`, `INELIGIBLE`, `FAILED` |
| `QUOTED` | Offer may be selected until expiry. | `SELECTED`, `EXPIRED`, `INELIGIBLE` |
| `SELECTED` | Offer has been selected for order-item creation. | `EXPIRED` |
| `EXPIRED` | Validity window elapsed. | Terminal for this quote; create a new quote to reprice. |
| `INELIGIBLE` | Minimum eligibility checks failed. | Terminal for this quote; create a new quote with new inputs. |
| `FAILED` | Catalog, supplier, or rule input failed. | Terminal for this quote. |

### AncillaryOrderItem status

This wave uses the nine-state lifecycle from the domain document and excludes the
future `COMPENSATED` closure from the wire contract.

| Status | Meaning | Allowed next statuses |
|---|---|---|
| `SELECTED` | User selected the item but it is not yet confirmed. | `PENDING_CONFIRMATION`, `CANCELLED` |
| `PENDING_CONFIRMATION` | Waiting for supplier, voucher, seat, entitlement, or primary-ticket condition. | `CONFIRMED`, `FAILED`, `CANCELLED` |
| `CONFIRMED` | Service is confirmed but not yet in the fulfillment window. | `FULFILLMENT_READY`, `CANCELLED`, `REFUND_PENDING` |
| `FULFILLMENT_READY` | Voucher, seat, consign, or service window is ready. | `FULFILLED`, `FAILED`, `CANCELLED` |
| `FULFILLED` | Service was redeemed or completed. | Terminal for this wave |
| `FAILED` | Confirmation or fulfillment failed. | `CANCELLED`, `REFUND_PENDING` |
| `CANCELLED` | User, system, main-journey cancellation, or supplier cancelled the item. | `REFUND_PENDING`, terminal |
| `REFUND_PENDING` | Refund/retain decision exists and awaits Payment execution outside this domain. | `REFUNDED` |
| `REFUNDED` | Payment/refund result was recorded for the ancillary item. | Terminal |

## Resource representations

### AncillaryCatalogItem

| Field | Type | Required | Description |
|---|---|---|---|
| `catalogItemId` | string | yes | Catalog item ID (`aci-<uuid>`). |
| `version` | integer | yes | Monotonically increasing catalog version. |
| `serviceType` | enum | yes | Ancillary service type. |
| `displayName` | string | yes | User/ops display name. |
| `attachmentScope` | enum | yes | Binding scope for the item. |
| `modalities` | string[] | yes | Applicable transport modes such as `TRAIN`, `AIR`, `BUS`, `FERRY`, or `TRANSFER`. |
| `supplierRef` | string | no | Normalized supplier capability reference. |
| `price` | Money | yes | Catalog fallback price used when Fare & Pricing dynamic rules are unavailable or inapplicable. |
| `currency` | string | yes | ISO-4217 currency matching `price.currency`; included for search/filter convenience. |
| `salesWindow` | object | yes | `startAt` and `endAt` RFC3339 UTC timestamps. |
| `serviceWindow` | object | no | Optional service-validity `startAt` and `endAt` timestamps. |
| `purchaseCutoffHoursBeforeDeparture` | integer | yes | Minimum whole hours before departure required to purchase. |
| `eligibilityRuleVersion` | string | yes | Version label for the minimum eligibility rule snapshot. |
| `requiresEntitlementRef` | boolean | yes | Whether a primary entitlement reference/assertion is required. |
| `requiresSegmentRef` | boolean | yes | Must be true for `SEGMENT` attachment scope and segment-bound service types. |
| `fulfillmentMethod` | enum | yes | `VOUCHER`, `PROVIDER_CONFIRMATION`, `MANUAL_OPS`, `NONE`. |
| `status` | enum | yes | Catalog status. |
| `supersededByCatalogItemId` | string | no | Replacement catalog item when status is `SUPERSEDED`. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last update timestamp. |

### AncillaryOffer

| Field | Type | Required | Description |
|---|---|---|---|
| `ancillaryOfferId` | string | yes | Offer ID (`aof-<uuid>`). |
| `offerVersion` | integer | yes | Offer version. |
| `status` | enum | yes | Offer status. |
| `journeyOrderId` | string | no | Journey order association for post-ticket add-ons, when known. |
| `offerRef` | string | no | Optional Offer Management offer reference for bundle display; no contract change is required upstream. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | no | Segment reference; required for segment-bound services. |
| `entitlementRef` | string | no | Caller-supplied entitlement proof or read-only entitlement lookup reference. |
| `catalogItemId` | string | yes | Quoted catalog item. |
| `catalogItemVersion` | integer | yes | Catalog version used by the quote. |
| `serviceType` | enum | yes | Service type copied from the catalog snapshot. |
| `attachmentScope` | enum | yes | Attachment scope copied from the catalog snapshot. |
| `quantity` | integer | yes | Positive quantity. |
| `unitPrice` | Money | yes | Quoted unit price from Fare & Pricing dynamic rules, or the catalog fallback price when dynamic pricing is unavailable. |
| `totalPrice` | Money | yes | `unitPrice * quantity` in minor units. |
| `eligibility` | object | yes | Minimum eligibility result. See `EligibilityResult`. |
| `validFrom` | RFC3339 UTC | yes | Quote validity start. |
| `expiresAt` | RFC3339 UTC | yes | Quote expiry. |
| `ruleSummary` | string | no | Human-readable rule summary; do not include sensitive personal data. |
| `priceQuoteRef` | object | no | Fare & Pricing quote reference: `{quoteId, inputHash, ruleSnapshot, source}` where `inputHash` and `ruleSnapshot` use `docs/08-contracts/events/fare-pricing.md`; `source` is `FARE_PRICING` or `CATALOG_FALLBACK`. |
| `assessedFees` | array[object] | no | Fare & Pricing fee components applied to the unit price; component shape is `{ruleId, amount, explanation, refundable}`. |
| `feeAssessment` | object | no | Fee assessment summary using Fare & Pricing fee-assessment fields: `{assessmentId, purpose, assessedAt, originalQuoteId, fee, currency, succeeded, failedReason}`. |
| `futurePricingRefs` | object | no | Optional informational refs for backward-compatible clients; normative pricing refs are `priceQuoteRef` and `feeAssessment`. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last update timestamp. |

`EligibilityResult` fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `status` | enum | yes | `ELIGIBLE`, `INELIGIBLE`, or `UNKNOWN`. |
| `primaryTicketStatus` | enum | yes | Primary ticket/entitlement status supplied or read. |
| `entitlementRef` | string | no | Entitlement proof used for this result. |
| `departureAt` | RFC3339 UTC | yes | Departure time used for the cutoff calculation. |
| `evaluatedAt` | RFC3339 UTC | yes | Eligibility evaluation timestamp. |
| `purchaseCutoffHoursBeforeDeparture` | integer | yes | Cutoff hours enforced. |
| `segmentRefRequired` | boolean | yes | Whether a segment reference was required. |
| `segmentRefPresent` | boolean | yes | Whether the request supplied a segment reference. |
| `reasons` | array[object] | yes | Reason objects with `code` and `message`; messages must not include unmasked documents. |

### AncillaryOrderItem

| Field | Type | Required | Description |
|---|---|---|---|
| `ancillaryOrderItemId` | string | yes | Order item ID (`aoi-<uuid>`). |
| `journeyOrderId` | string | yes | Owning Journey Order reference; Journey Order contract is unchanged. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | no | Segment binding where applicable. |
| `entitlementRef` | string | no | Entitlement proof or service entitlement/voucher reference. |
| `ancillaryOfferId` | string | yes | Selected offer. |
| `offerVersion` | integer | yes | Selected offer version. |
| `catalogItemId` | string | yes | Catalog item snapshot used. |
| `catalogItemVersion` | integer | yes | Catalog version snapshot used. |
| `serviceType` | enum | yes | Service type. |
| `attachmentScope` | enum | yes | Attachment scope. |
| `quantity` | integer | yes | Positive quantity. |
| `payableAmount` | Money | yes | Informational amount due for Payment/Journey Order coordination. |
| `refundableAmount` | Money | yes | Informational amount currently recommended as refundable. |
| `assessedFees` | array[object] | yes | Assessed fee components copied from the selected quote and multiplied by quantity; component shape is `{ruleId, amount, explanation, refundable}`. |
| `feeAssessment` | object | no | Fee assessment summary copied from the selected quote and multiplied by quantity; shape is `{assessmentId, purpose, assessedAt, originalQuoteId, fee, currency, succeeded, failedReason}`. |
| `priceQuoteRef` | object | no | Fare & Pricing quote reference copied from the selected quote: `{quoteId, inputHash, ruleSnapshot, source}`. |
| `refundRecommendation` | enum | no | Latest refund suggestion, if evaluated. |
| `refundReason` | string | no | Stable reason code or summary; no unmasked sensitive personal data. |
| `status` | enum | yes | Nine-state order-item status. |
| `statusReason` | string | no | Reason for latest transition. |
| `fulfillmentFacts` | array[object] | yes | Embedded service fulfillment facts. See `FulfillmentFact`. |
| `selectedAt` | RFC3339 UTC | yes | Selection timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last transition timestamp. |

`FulfillmentFact` fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `fulfillmentFactId` | string | yes | Fact ID (`aff-<uuid>`). |
| `factType` | enum | yes | Fulfillment fact type. |
| `providerRef` | string | no | Normalized provider reference, voucher, seat, baggage, policy, or pickup reference. |
| `placeRef` | string | no | Place where the fact happened. |
| `occurredAt` | RFC3339 UTC | yes | Fact occurrence time. |
| `recordedAt` | RFC3339 UTC | yes | Time Ancillary Service recorded the fact. |
| `performedBy` | enum | yes | `SYSTEM`, `PROVIDER`, `OPS`, `CUSTOMER_SERVICE`. |
| `idempotencyRef` | string | yes | Provider event, voucher redemption, or ops command reference used for deduplication. |
| `compensable` | boolean | yes | Whether a failed fact can be compensated in a later wave. |
| `notes` | string | no | Operational notes; must not contain unmasked sensitive personal data. |

## Endpoints

### Create Catalog Item

**POST** `/api/v1/ancillary-catalog-items`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request:** catalog fields needed to create a draft item: `serviceType`,
`displayName`, `attachmentScope`, `modalities`, `supplierRef`, `price`,
`salesWindow`, optional `serviceWindow`, `purchaseCutoffHoursBeforeDeparture`,
`eligibilityRuleVersion`, `requiresEntitlementRef`, `requiresSegmentRef`, and
`fulfillmentMethod`.

**Response (201):** `AncillaryCatalogItem` with status `DRAFT`.

**Error codes:** `VALIDATION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Update Draft Catalog Item

**PUT** `/api/v1/ancillary-catalog-items/{catalogItemId}`

**Idempotency:** REQUIRED (`Idempotency-Key` header). Only `DRAFT` items may be
updated. Published rule, price, or fulfillment changes require supersession.

**Request:** same mutable catalog fields as create, plus `expectedVersion`.

**Response (200):** updated `AncillaryCatalogItem`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Delete Draft Catalog Item

**DELETE** `/api/v1/ancillary-catalog-items/{catalogItemId}`

**Idempotency:** REQUIRED (`Idempotency-Key` header). Only `DRAFT` items may be
deleted. Published, suspended, superseded, or expired items remain in the audit
trail and must use lifecycle transitions instead.

**Response (204):** empty body.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Publish Catalog Item

**POST** `/api/v1/ancillary-catalog-items/{catalogItemId}/publish`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request fields:** `approvalRef` (string, required), `effectiveAt` (RFC3339 UTC,
optional), `expectedVersion` (integer, required).

**Response (200):** `AncillaryCatalogItem` with status `PUBLISHED`.

**Domain effects:** emits `AncillaryCatalogItemPublished`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Suspend Catalog Item

**POST** `/api/v1/ancillary-catalog-items/{catalogItemId}/suspend`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request fields:** `reasonCode` (string, required), `effectiveAt` (RFC3339 UTC,
optional), `expectedVersion` (integer, required).

**Response (200):** `AncillaryCatalogItem` with status `SUSPENDED`.

**Domain effects:** emits `AncillaryCatalogItemSuspended`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Supersede Catalog Item

**POST** `/api/v1/ancillary-catalog-items/{catalogItemId}/supersede`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request fields:** `replacementCatalogItemId` (string, required), `reasonCode`
(string, required), `effectiveAt` (RFC3339 UTC, optional), `expectedVersion`
(integer, required).

**Response (200):** `AncillaryCatalogItem` with status `SUPERSEDED`.

**Domain effects:** emits `AncillaryCatalogItemSuperseded`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Expire Catalog Item

**POST** `/api/v1/ancillary-catalog-items/{catalogItemId}/expire`

**Idempotency:** REQUIRED (`Idempotency-Key` header). Used by operations or a
scheduler when sale/service validity has elapsed.

**Request fields:** `reasonCode` (string, required), `expiredAt` (RFC3339 UTC,
required), `expectedVersion` (integer, required).

**Response (200):** `AncillaryCatalogItem` with status `EXPIRED`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Get Catalog Item

**GET** `/api/v1/ancillary-catalog-items/{catalogItemId}`

**Response (200):** `AncillaryCatalogItem`.

**Error codes:** `NOT_FOUND`

### List Catalog Items

**GET** `/api/v1/ancillary-catalog-items?status=PUBLISHED&serviceType=MEAL&limit=20&offset=0`

**Query parameters:** optional `status`, optional `serviceType`, optional
`attachmentScope`, plus pagination.

**Response (200):** paginated response with `items`, `total`, `limit`, and
`offset`, where each item is an `AncillaryCatalogItem`.

**Error codes:** `VALIDATION_FAILED`

### Draft Ancillary Offer

**POST** `/api/v1/ancillary-offers`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `catalogItemId` | string | yes | Published catalog item to quote. |
| `journeyOrderId` | string | no | Existing Journey Order for add-on purchases, if any. |
| `offerRef` | string | no | Optional Offer Management reference for bundle display. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | conditional | Required when the catalog item requires segment binding. |
| `entitlementRef` | string | no | Caller-supplied entitlement proof. If absent and required, the service may perform a read-only entitlement lookup. |
| `primaryTicketStatus` | enum | no | Caller-supplied status assertion when no entitlement lookup is used. |
| `departureAt` | RFC3339 UTC | yes | Departure time for purchase cutoff evaluation. |
| `quantity` | integer | yes | Positive quantity. |

**Response (201):** `AncillaryOffer` in `DRAFTING`, `INELIGIBLE`, or `FAILED`
status after minimum eligibility collection.

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Quote Ancillary Offer

**POST** `/api/v1/ancillary-offers/{ancillaryOfferId}/quote`

**Idempotency:** REQUIRED (`Idempotency-Key` header). Pricing consults Fare &
Pricing dynamic rules with catalog price fallback; the service forwards the same
client-generated UUID v7 key to Fare & Pricing for safe retry of the quote
request.

**Request fields:** `expectedVersion` (integer, required), optional
`validitySeconds` (integer), and optional `clientRequestId` (string).

**Response (200):** `AncillaryOffer` with status `QUOTED`, `INELIGIBLE`, or
`FAILED`.

**Domain effects:** emits `AncillaryOfferQuoted` when quoted.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Select Ancillary Offer

**POST** `/api/v1/ancillary-offers/{ancillaryOfferId}/select`

**Idempotency:** REQUIRED (`Idempotency-Key` header). The offer must be `QUOTED`,
not expired, and still eligible.

**Request fields:** `journeyOrderId` (string, required), `expectedVersion`
(integer, required), and optional `clientRequestId` (string).

**Response (201):** `AncillaryOrderItem` with status `SELECTED`.

**Domain effects:** moves the offer to `SELECTED` and emits
`AncillaryOrderItemSelected`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Get Ancillary Offer

**GET** `/api/v1/ancillary-offers/{ancillaryOfferId}`

**Response (200):** `AncillaryOffer`.

**Error codes:** `NOT_FOUND`

### Confirm Ancillary Order Item

**POST** `/api/v1/ancillary-order-items/{ancillaryOrderItemId}/confirm`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request fields:** `confirmationRef` (string, optional), `entitlementRef`
(string, optional), `expectedStatus` (enum, optional), and `reasonCode` (string,
optional).

**Response (200):** `AncillaryOrderItem` in `PENDING_CONFIRMATION` or
`CONFIRMED` according to the confirmation stage.

**Domain effects:** emits `AncillaryOrderItemPendingConfirmation` or
`AncillaryOrderItemConfirmed`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Mark Fulfillment Ready

**POST** `/api/v1/ancillary-order-items/{ancillaryOrderItemId}/fulfillment-ready`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request fields:** optional `providerRef`, optional `entitlementRef`, optional
`reasonCode`, and optional `readyAt` RFC3339 UTC.

**Response (200):** `AncillaryOrderItem` with status `FULFILLMENT_READY`.

**Domain effects:** emits `AncillaryOrderItemFulfillmentReady`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Cancel Ancillary Order Item

**POST** `/api/v1/ancillary-order-items/{ancillaryOrderItemId}/cancel`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request fields:** `reasonCode` (string, required), `source` (enum `USER`,
`OPS`, `SYSTEM`, `JOURNEY_ORDER_CANCELLED`, required), optional `sourceEventId`,
optional `cancelledAt` RFC3339 UTC, and optional `expectedStatus`.

**Response (200):** `AncillaryOrderItem` with status `CANCELLED`.

**Domain effects:** emits `AncillaryOrderItemCancelled`. When triggered by the
`JourneyOrderCancelled` subscription, `source` is `JOURNEY_ORDER_CANCELLED` and
`sourceEventId` is the consumed Journey Order event ID.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Suggest Ancillary Refund

**POST** `/api/v1/ancillary-order-items/{ancillaryOrderItemId}/refund-suggestions`

**Idempotency:** REQUIRED (`Idempotency-Key` header). This endpoint does not call
Payment and does not settle funds.

**Request fields:** `reasonCode` (string, required), optional `postSalesCaseId`,
optional `waiverRef`, optional `requestedAt` RFC3339 UTC.

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `ancillaryOrderItem` | AncillaryOrderItem | Updated item, normally `REFUND_PENDING` when a refund is due. |
| `recommendation` | enum | `FULL_REFUND`, `PARTIAL_REFUND`, `NO_REFUND`, or `MANUAL_REVIEW`. |
| `refundableAmount` | Money | Suggested refundable amount. |
| `reasonCode` | string | Stable refund reason code. |

**Domain effects:** emits `AncillaryOrderItemRefundPending` when a refund is due.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Record Ancillary Refunded

**POST** `/api/v1/ancillary-order-items/{ancillaryOrderItemId}/refunded`

**Idempotency:** REQUIRED (`Idempotency-Key` header). Records an external Payment
or operations outcome; Ancillary Service does not execute the refund.

**Request fields:** `refundRef` (string, required), `refundedAmount` (Money,
required), `refundedAt` (RFC3339 UTC, required), optional `reasonCode`.

**Response (200):** `AncillaryOrderItem` with status `REFUNDED`.

**Domain effects:** emits `AncillaryOrderItemRefunded`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Record Fulfillment Fact

**POST** `/api/v1/ancillary-order-items/{ancillaryOrderItemId}/fulfillment-facts`

**Idempotency:** REQUIRED (`Idempotency-Key` header). Duplicate provider or ops
facts are idempotent on `factType` plus `idempotencyRef`.

**Request fields:** `factType` (enum, required), optional `providerRef`, optional
`placeRef`, `occurredAt` (RFC3339 UTC, required), `performedBy` (enum, required),
`idempotencyRef` (string, required), optional `compensable` (boolean, default
false), and optional `notes`.

**Response (200):** `AncillaryOrderItem` with the appended `fulfillmentFacts`
entry. If the fact completes the service, status may become `FULFILLED`; if it
records provider failure, status may become `FAILED`.

**Domain effects:** emits `AncillaryFulfillmentFactRecorded` and, when the state
changes, the corresponding order-item lifecycle event.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Get Ancillary Order Item

**GET** `/api/v1/ancillary-order-items/{ancillaryOrderItemId}`

**Response (200):** `AncillaryOrderItem`.

**Error codes:** `NOT_FOUND`

### List Ancillary Order Items by Journey Order

**GET** `/api/v1/ancillary-order-items?journeyOrderId={journeyOrderId}&limit=20&offset=0`

`journeyOrderId` is required. Broad unfiltered listing is not part of this wave.

**Query parameters:** required `journeyOrderId`, optional `status`, optional
`travelerRef`, optional `segmentRef`, plus pagination.

**Response (200):** paginated response with `items`, `total`, `limit`, and
`offset`, where each item is an `AncillaryOrderItem`.

**Error codes:** `VALIDATION_FAILED`

## Bus-only behavior

The following behaviors have no public HTTP endpoint in this activation wave:

- `ExpireAncillaryOffer` — scheduler/domain policy moves elapsed quoted offers to
  `EXPIRED` and emits `AncillaryOfferExpired`.
- `HandleJourneyOrderCancelled` — event consumer for `JourneyOrderCancelled` from
  `events:journey-order`; cancels associated non-terminal ancillary order items
  and emits `AncillaryOrderItemCancelled` with source `JOURNEY_ORDER_CANCELLED`.

## Deferred integrations and explicit non-changes

- Journey Order contracts are not modified by this wave. Ancillary references are
  stored and listed by this service; any Journey Order projection is future work.
- Payment contracts are not modified. `payableAmount` and `refundableAmount` are
  information fields for future orchestration and external refund-result
  recording.
- Fare & Pricing integration is active for quoted offers and selected order
  items. Ancillary Service uses the normative fare-pricing input hash and rule
  snapshot contract, carries assessed fee facts, and falls back to catalog
  `price` only when dynamic pricing is unavailable or inapplicable.
- Additional eligibility dimensions from the domain document remain deferred:
  traveler age/documents, special needs, supplier slot capacity, provider status,
  fare-rule refundability, and bundle split rules.
