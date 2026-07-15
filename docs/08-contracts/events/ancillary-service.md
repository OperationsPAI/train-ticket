# Ancillary Service — Events & Commands

Last updated: 2026-07-15

## Scope and activation-wave rulings

This contract enumerates the Ancillary Service events for the ADR-0002 third
activation wave. It is bounded by `docs/02-domains/ancillary-service.md` and the
HTTP contract in `docs/08-contracts/api/ancillary-service.md`.

Activation-wave rulings:

- This wave activates the three aggregate roots `AncillaryCatalogItem`,
  `AncillaryOffer`, and `AncillaryOrderItem`. The domain's former
  `ServiceFulfillmentRecord` concept is represented as embedded
  `fulfillmentFacts` on `AncillaryOrderItem` and as
  `AncillaryFulfillmentFactRecorded` events.
- Fare & Pricing dynamic price rules are authoritative when available. Event
  payloads carry `Money` fields copied from the quoted offer/order snapshots,
  the Fare & Pricing `RuleSnapshot`/`inputHash` references, and assessed fees.
  Catalog price remains the fallback price when Fare & Pricing is unavailable.
- Ancillary Service does not modify Journey Order, Payment, Fare & Pricing, or
  Entitlement contracts. Cross-context references are carried as strings such as
  `journeyOrderId`, `travelerRef`, `segmentRef`, `entitlementRef`, and optional
  future refs.
- Minimum eligibility is limited to primary-ticket status, purchase cutoff before
  departure, and mandatory segment binding for segment-bound services. Other
  eligibility dimensions in the domain document are deferred.
- Ancillary Service subscribes only to `JourneyOrderCancelled` from
  `events:journey-order` in this wave. It cancels associated non-terminal order
  items idempotently and emits normal cancellation lifecycle facts.
- Finance Settlement, Notification, Post Sales, Journey Order, Offer Management,
  Fulfillment, Payment, and Reporting now consume the event subsets listed below.
  Catalog-item notifications remain a documented future touchpoint until the
  notification service maps those event types.

All payload fields are camelCase, all enum values are SCREAMING_SNAKE_CASE, and
all timestamps are RFC3339 UTC. Envelope fields, including optional trace context
propagation, follow `docs/08-contracts/messaging.md` and
`docs/08-contracts/shared-primitives.md`. Monetary values use `Money` as
`{currency, minorUnits}`.

## Event identity and idempotency

Ancillary Service producers MUST assign deterministic event IDs per aggregate
transition so retries do not create duplicate facts. The event ID seed is:

```
ancillary-service:<eventType>:<aggregateId>:<aggregateVersion>
```

where `aggregateId` is `catalogItemId`, `ancillaryOfferId`, or
`ancillaryOrderItemId`, and `aggregateVersion` is the version after applying the
transition. Fulfillment facts use:

```
ancillary-service:AncillaryFulfillmentFactRecorded:<ancillaryOrderItemId>:<fulfillmentFactId>
```

If a command replay does not apply a new transition or append a new fulfillment
fact, no new event is emitted. Consumers still deduplicate by envelope `eventId`.

## Shared payload objects

### Money

All monetary values use the shared `Money` shape: `currency` plus integer
`minorUnits`. No decimal strings or floating-point amounts are used.

### EligibilityResult

| Field | Type | Required | Description |
|---|---|---|---|
| `status` | enum | yes | `ELIGIBLE`, `INELIGIBLE`, or `UNKNOWN`. |
| `primaryTicketStatus` | enum | yes | `ISSUED`, `CONFIRMED`, `PENDING`, `CANCELLED`, `VOIDED`, or `UNKNOWN`. |
| `entitlementRef` | string | no | Entitlement proof used for this result. |
| `departureAt` | RFC3339 UTC | yes | Departure time used for cutoff evaluation. |
| `evaluatedAt` | RFC3339 UTC | yes | Eligibility evaluation time. |
| `purchaseCutoffHoursBeforeDeparture` | integer | yes | Cutoff hours enforced. |
| `segmentRefRequired` | boolean | yes | Whether segment binding was required. |
| `segmentRefPresent` | boolean | yes | Whether the request supplied `segmentRef`. |
| `reasons` | array[object] | yes | Reason objects with `code` and `message`; messages must not include unmasked sensitive personal data. |

### CatalogSnapshot

| Field | Type | Required | Description |
|---|---|---|---|
| `catalogItemId` | string | yes | Catalog item ID (`aci-<uuid>`). |
| `catalogItemVersion` | integer | yes | Catalog version. |
| `serviceType` | enum | yes | `INSURANCE`, `MEAL`, `BAGGAGE`, `CONSIGN`, `SEAT_SELECTION`, `TRANSFER_PICKUP`, `LOUNGE`, `FAST_TRACK`, or `BUNDLE`. |
| `attachmentScope` | enum | yes | `JOURNEY`, `SEGMENT`, `TRAVELER`, `ENTITLEMENT`, `PLACE`, or `TRANSFER`. |
| `displayName` | string | yes | Display name captured at quote/order time. |
| `unitPrice` | Money | yes | Catalog fallback unit price captured at quote/order time. |
| `purchaseCutoffHoursBeforeDeparture` | integer | yes | Minimum cutoff used by eligibility. |
| `eligibilityRuleVersion` | string | yes | Eligibility rule snapshot version. |
| `fulfillmentMethod` | enum | yes | `VOUCHER`, `PROVIDER_CONFIRMATION`, `MANUAL_OPS`, or `NONE`. |

### FulfillmentFact

| Field | Type | Required | Description |
|---|---|---|---|
| `fulfillmentFactId` | string | yes | Fact ID (`aff-<uuid>`). |
| `factType` | enum | yes | One of the fulfillment fact types listed below. |
| `providerRef` | string | no | Normalized provider/voucher/seat/baggage/policy/pickup reference. |
| `placeRef` | string | no | Place reference for the fact. |
| `occurredAt` | RFC3339 UTC | yes | When the fact happened. |
| `recordedAt` | RFC3339 UTC | yes | When Ancillary Service recorded it. |
| `performedBy` | enum | yes | `SYSTEM`, `PROVIDER`, `OPS`, or `CUSTOMER_SERVICE`. |
| `idempotencyRef` | string | yes | Provider event, voucher redemption, or ops command reference used for deduplication. |
| `compensable` | boolean | yes | Whether this fact can be compensated in a later wave. |
| `notes` | string | no | Operational notes; must not contain unmasked sensitive personal data. |

`factType` values are `MEAL_ISSUED`, `BAGGAGE_CHECKED`, `CONSIGN_ACCEPTED`,
`CONSIGN_DELIVERED`, `SEAT_ASSIGNED`, `LOUNGE_REDEEMED`, `FAST_TRACK_USED`,
`PICKUP_COMPLETED`, `INSURANCE_ACTIVATED`, `INSURANCE_VOIDED`,
`SERVICE_VOUCHER_ISSUED`, `SERVICE_VOUCHER_REDEEMED`, and
`PROVIDER_FULFILLMENT_FAILED`.

## Published Events

### AncillaryCatalogItemPublished

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | offer-management, reporting |
| **Trigger** | `PublishCatalogItem` command publishes a configured catalog item. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `catalogItemId` | string | yes | Catalog item ID. |
| `version` | integer | yes | Published version. |
| `serviceType` | enum | yes | Ancillary service type. |
| `displayName` | string | yes | Display name. |
| `attachmentScope` | enum | yes | Binding scope. |
| `modalities` | string[] | yes | Applicable transport modalities. |
| `supplierRef` | string | no | Normalized supplier capability reference. |
| `price` | Money | yes | Catalog fallback price. |
| `salesWindow` | object | yes | Sale `startAt` and `endAt` timestamps. |
| `serviceWindow` | object | no | Service `startAt` and `endAt` timestamps when applicable. |
| `purchaseCutoffHoursBeforeDeparture` | integer | yes | Purchase cutoff. |
| `eligibilityRuleVersion` | string | yes | Eligibility rule version. |
| `requiresEntitlementRef` | boolean | yes | Whether entitlement proof is required. |
| `requiresSegmentRef` | boolean | yes | Whether segment reference is required. |
| `fulfillmentMethod` | enum | yes | Fulfillment method. |
| `status` | enum | yes | `PUBLISHED`. |
| `publishedAt` | RFC3339 UTC | yes | Publish timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after publish. |

### AncillaryCatalogItemSuspended

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | offer-management, reporting |
| **Trigger** | `SuspendCatalogItem` command pauses sale of a published or suspended item. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `catalogItemId` | string | yes | Catalog item ID. |
| `version` | integer | yes | Catalog version. |
| `serviceType` | enum | yes | Ancillary service type. |
| `reasonCode` | string | yes | Stable suspension reason. |
| `previousStatus` | enum | yes | Previous catalog status. |
| `status` | enum | yes | `SUSPENDED`. |
| `suspendedAt` | RFC3339 UTC | yes | Suspension timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after suspension. |

### AncillaryCatalogItemSuperseded

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | offer-management, reporting |
| **Trigger** | `SupersedeCatalogItem` command replaces a catalog version. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `catalogItemId` | string | yes | Superseded catalog item ID. |
| `version` | integer | yes | Superseded version. |
| `replacementCatalogItemId` | string | yes | Replacement catalog item ID. |
| `serviceType` | enum | yes | Ancillary service type. |
| `reasonCode` | string | yes | Stable supersession reason. |
| `previousStatus` | enum | yes | Previous catalog status. |
| `status` | enum | yes | `SUPERSEDED`. |
| `supersededAt` | RFC3339 UTC | yes | Supersession timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after supersession. |

### AncillaryOfferQuoted

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | offer-management, journey-order, notification, reporting |
| **Trigger** | `QuoteAncillaryOffer` command succeeds after minimum eligibility and Fare & Pricing dynamic-rule pricing or catalog fallback pricing. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `ancillaryOfferId` | string | yes | Offer ID (`aof-<uuid>`). |
| `offerVersion` | integer | yes | Offer version. |
| `journeyOrderId` | string | no | Journey order association for post-ticket add-ons, if known. |
| `offerRef` | string | no | Optional Offer Management reference. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | no | Segment reference for segment-bound services. |
| `entitlementRef` | string | no | Entitlement proof or lookup reference. |
| `catalogSnapshot` | object | yes | Catalog snapshot used for the quote. |
| `quantity` | integer | yes | Positive quantity. |
| `unitPrice` | Money | yes | Dynamic-rule unit price when Fare & Pricing quoted successfully; otherwise catalog fallback unit price. |
| `totalPrice` | Money | yes | Quoted total. |
| `assessedFees` | array[object] | no | Unit-level assessed fee components `{ruleId, amount, explanation, refundable}`. |
| `feeAssessment` | object | no | Fee assessment summary `{assessmentId, purpose, assessedAt, originalQuoteId, fee, currency, succeeded, failedReason}`. |
| `priceQuoteRef` | object | no | Fare & Pricing quote reference `{quoteId, inputHash, ruleSnapshot, source}`. |
| `eligibility` | object | yes | Minimum eligibility result. |
| `validFrom` | RFC3339 UTC | yes | Quote validity start. |
| `expiresAt` | RFC3339 UTC | yes | Quote expiry. |
| `status` | enum | yes | `QUOTED`. |
| `quotedAt` | RFC3339 UTC | yes | Quote timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after quote. |

### AncillaryOfferExpired

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | offer-management, journey-order, notification, reporting |
| **Trigger** | Quote validity window elapsed or explicit scheduler/domain expiry. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `ancillaryOfferId` | string | yes | Expired ancillary offer ID. |
| `offerVersion` | integer | yes | Version at expiry. |
| `travelerRef` | string | yes | Traveler reference. |
| `journeyOrderId` | string | no | Associated Journey Order, if any. |
| `catalogItemId` | string | yes | Quoted catalog item. |
| `previousStatus` | enum | yes | `QUOTED` or `SELECTED`. |
| `reason` | enum | yes | `VALIDITY_WINDOW_ELAPSED` or `EXPLICIT_EXPIRE_COMMAND`. |
| `expiredAt` | RFC3339 UTC | yes | Expiry timestamp. |
| `status` | enum | yes | `EXPIRED`. |
| `aggregateVersion` | integer | yes | Aggregate version after expiry. |

### AncillaryOrderItemSelected

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | journey-order, notification, reporting |
| **Trigger** | `SelectAncillaryOffer` creates an ancillary order item from a valid quote. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `ancillaryOrderItemId` | string | yes | Order item ID (`aoi-<uuid>`). |
| `journeyOrderId` | string | yes | Referenced Journey Order. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | no | Segment binding if applicable. |
| `entitlementRef` | string | no | Entitlement proof if supplied. |
| `ancillaryOfferId` | string | yes | Source offer. |
| `offerVersion` | integer | yes | Source offer version. |
| `catalogSnapshot` | object | yes | Catalog snapshot copied from the offer. |
| `quantity` | integer | yes | Positive quantity. |
| `payableAmount` | Money | yes | Informational amount payable outside this domain. |
| `refundableAmount` | Money | yes | Initial refundable amount, normally zero or the payable amount by policy. |
| `assessedFees` | array[object] | yes | Assessed fee components copied from the selected quote and multiplied by quantity. |
| `feeAssessment` | object | no | Fee assessment summary copied from the selected quote and multiplied by quantity. |
| `priceQuoteRef` | object | no | Fare & Pricing quote reference copied from the selected quote. |
| `status` | enum | yes | `SELECTED`. |
| `selectedAt` | RFC3339 UTC | yes | Selection timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after selection. |

### AncillaryOrderItemPendingConfirmation

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | journey-order, notification, reporting |
| **Trigger** | `ConfirmAncillaryItem` begins supplier/voucher/entitlement confirmation but is not final. |

**Payload fields:** same identity, reference, `payableAmount`, and
`refundableAmount` fields as `AncillaryOrderItemSelected`, plus `confirmationRef`
(optional string), `reasonCode` (optional string), `previousStatus`, `status` =
`PENDING_CONFIRMATION`, `transitionedAt`, and `aggregateVersion`.

### AncillaryOrderItemConfirmed

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | journey-order, notification, reporting |
| **Trigger** | `ConfirmAncillaryItem` confirms the service after required prerequisites. |

**Payload fields:** same identity, reference, `payableAmount`, and
`refundableAmount` fields as `AncillaryOrderItemSelected`, plus optional
`confirmationRef`, optional `entitlementRef`, `previousStatus`, `status` =
`CONFIRMED`, `confirmedAt`, and `aggregateVersion`.

### AncillaryOrderItemFulfillmentReady

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | fulfillment, journey-order, notification, reporting |
| **Trigger** | `MarkFulfillmentReady` records voucher, seat, consign, or service-window readiness. |

**Payload fields:** same identity/reference fields as
`AncillaryOrderItemSelected`, plus optional `providerRef`, optional
`entitlementRef`, `previousStatus`, `status` = `FULFILLMENT_READY`, `readyAt`, and
`aggregateVersion`.

### AncillaryOrderItemFulfilled

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | journey-order, finance-settlement, notification, post-sales, reporting |
| **Trigger** | A fulfillment fact completes the service, such as meal issued, lounge redeemed, fast track used, pickup completed, baggage/consign completed, or insurance activated. |

**Payload fields:** same identity/reference fields as
`AncillaryOrderItemSelected`, plus `fulfillmentFact` (object), `previousStatus`,
`status` = `FULFILLED`, `fulfilledAt`, and `aggregateVersion`.

### AncillaryOrderItemFailed

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | journey-order, notification, post-sales, reporting |
| **Trigger** | Confirmation or fulfillment fails, including provider fulfillment failure facts. |

**Payload fields:** same identity/reference fields as
`AncillaryOrderItemSelected`, plus `failureCode` (string), optional
`fulfillmentFact`, `compensable` (boolean), `previousStatus`, `status` =
`FAILED`, `failedAt`, and `aggregateVersion`.

### AncillaryOrderItemCancelled

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | journey-order, finance-settlement, notification, post-sales, reporting |
| **Trigger** | Explicit cancel command or `JourneyOrderCancelled` consumed from Journey Order. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `ancillaryOrderItemId` | string | yes | Order item ID. |
| `journeyOrderId` | string | yes | Referenced Journey Order. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | no | Segment binding if applicable. |
| `catalogItemId` | string | yes | Catalog item. |
| `serviceType` | enum | yes | Ancillary service type. |
| `payableAmount` | Money | yes | Informational payable amount. |
| `refundableAmount` | Money | yes | Current refundable suggestion. |
| `reasonCode` | string | yes | Stable cancellation reason. |
| `source` | enum | yes | `USER`, `OPS`, `SYSTEM`, `SUPPLIER`, or `JOURNEY_ORDER_CANCELLED`. |
| `sourceEventId` | string | no | Consumed event ID when cancellation follows `JourneyOrderCancelled`. |
| `previousStatus` | enum | yes | Previous order-item status. |
| `status` | enum | yes | `CANCELLED`. |
| `cancelledAt` | RFC3339 UTC | yes | Cancellation timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after cancellation. |

### AncillaryOrderItemRefundPending

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | payment, finance-settlement, notification, post-sales, reporting |
| **Trigger** | `SuggestAncillaryRefund` determines a refund is due; Payment execution is deferred. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `ancillaryOrderItemId` | string | yes | Order item ID. |
| `journeyOrderId` | string | yes | Referenced Journey Order. |
| `travelerRef` | string | yes | Traveler reference. |
| `catalogItemId` | string | yes | Catalog item. |
| `serviceType` | enum | yes | Ancillary service type. |
| `payableAmount` | Money | yes | Original informational payable amount. |
| `refundableAmount` | Money | yes | Suggested refundable amount. |
| `recommendation` | enum | yes | `FULL_REFUND`, `PARTIAL_REFUND`, `NO_REFUND`, or `MANUAL_REVIEW`. |
| `reasonCode` | string | yes | Refund suggestion reason. |
| `postSalesCaseId` | string | no | Post Sales case reference when supplied. |
| `previousStatus` | enum | yes | Previous order-item status. |
| `status` | enum | yes | `REFUND_PENDING` when a refund is due; otherwise the item remains in its previous status and no event is emitted. |
| `requestedAt` | RFC3339 UTC | yes | Refund suggestion timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after transition. |

### AncillaryOrderItemRefunded

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | journey-order, finance-settlement, notification, post-sales, reporting |
| **Trigger** | `RecordAncillaryRefunded` records external Payment or operations refund completion. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `ancillaryOrderItemId` | string | yes | Order item ID. |
| `journeyOrderId` | string | yes | Referenced Journey Order. |
| `travelerRef` | string | yes | Traveler reference. |
| `catalogItemId` | string | yes | Catalog item. |
| `serviceType` | enum | yes | Ancillary service type. |
| `refundRef` | string | yes | External refund reference. |
| `refundedAmount` | Money | yes | Amount recorded as refunded. |
| `previousStatus` | enum | yes | Previous order-item status. |
| `status` | enum | yes | `REFUNDED`. |
| `refundedAt` | RFC3339 UTC | yes | Refund completion timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after refund record. |

### AncillaryFulfillmentFactRecorded

| Field | Description |
|---|---|
| **Producer** | ancillary-service |
| **Consumers** | fulfillment, finance-settlement, notification, post-sales, reporting |
| **Trigger** | `RecordFulfillmentFact` appends an idempotent fulfillment fact to an order item. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `ancillaryOrderItemId` | string | yes | Order item ID. |
| `journeyOrderId` | string | yes | Referenced Journey Order. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | no | Segment binding if applicable. |
| `catalogItemId` | string | yes | Catalog item. |
| `serviceType` | enum | yes | Ancillary service type. |
| `fulfillmentFact` | object | yes | Embedded fulfillment fact. |
| `previousStatus` | enum | yes | Status before applying the fact. |
| `status` | enum | yes | Current order-item status after applying the fact. |
| `aggregateVersion` | integer | yes | Aggregate version after recording the fact. |

## Accepted Commands

| Command | Sender / Trigger | Produced event |
|---|---|---|
| `CreateCatalogItem` | API gateway / operations via `POST /api/v1/ancillary-catalog-items` | no integration event in this wave |
| `DeleteDraftCatalogItem` | API gateway / operations via `DELETE /api/v1/ancillary-catalog-items/{catalogItemId}` for draft-only cleanup | no integration event in this wave |
| `PublishCatalogItem` | API gateway / operations via `/publish` | `AncillaryCatalogItemPublished` |
| `SuspendCatalogItem` | API gateway / operations via `/suspend` | `AncillaryCatalogItemSuspended` |
| `SupersedeCatalogItem` | API gateway / operations via `/supersede` | `AncillaryCatalogItemSuperseded` |
| `QuoteAncillaryOffer` | API gateway/UI via `/quote` after draft collection | `AncillaryOfferQuoted` |
| `ExpireAncillaryOffer` | Scheduler/domain policy | `AncillaryOfferExpired` |
| `SelectAncillaryOffer` | API gateway/UI via `/select` | `AncillaryOrderItemSelected` |
| `ConfirmAncillaryItem` | API gateway/ops/orchestration via `/confirm` | `AncillaryOrderItemPendingConfirmation` or `AncillaryOrderItemConfirmed` |
| `MarkFulfillmentReady` | API gateway/ops/provider ACL via `/fulfillment-ready` | `AncillaryOrderItemFulfillmentReady` |
| `RecordFulfillmentFact` | API gateway/ops/provider ACL via `/fulfillment-facts` | `AncillaryFulfillmentFactRecorded` plus optional lifecycle event |
| `CancelAncillaryItem` | API gateway/ops or `JourneyOrderCancelled` consumer | `AncillaryOrderItemCancelled` |
| `SuggestAncillaryRefund` | API gateway/post-sales workflow via `/refund-suggestions` | `AncillaryOrderItemRefundPending` when refund is due |
| `RecordAncillaryRefunded` | API gateway/ops/payment-result recorder via `/refunded` | `AncillaryOrderItemRefunded` |

## Consumed upstream events

### JourneyOrderCancelled

| Field | Description |
|---|---|
| **Source stream** | `events:journey-order` |
| **Consumer group** | `ancillary-service` |
| **Purpose** | Automatically cancel non-terminal `AncillaryOrderItem` records associated with the cancelled Journey Order. |

Required consumed payload fields from the Journey Order event are `orderId`
(the producer-contract field name in events/journey-order.md, mapped internally
to this domain's `journeyOrderId`) and the envelope `eventId`/`occurredAt`. Ancillary Service MUST process this event
through its inbox/consumed-event log. For each associated order item not already
terminal (`FULFILLED`, `REFUNDED`, or terminal `CANCELLED`), it emits
`AncillaryOrderItemCancelled` with:

- `source = JOURNEY_ORDER_CANCELLED`
- `sourceEventId = <consumed envelope eventId>`
- `reasonCode = JOURNEY_ORDER_CANCELLED`

No Journey Order contract shape is changed by this subscription.
