# Messaging Contract — Redis Streams Event Bus

**Last updated:** 2026-07-09
**Spec version:** 1.0.0  
**ADR:** [0001-event-bus-redis-streams.md](../adr/0001-event-bus-redis-streams.md)

---

## Table of Contents

1. [Middleware Decision](#middleware-decision)
2. [Stream Naming](#stream-naming)
3. [Message Format](#message-format)
4. [Consumer Groups](#consumer-groups)
5. [Required Subscriptions](#required-subscriptions)
6. [Delivery Semantics](#delivery-semantics)
7. [Ordering Guarantees](#ordering-guarantees)
8. [Retention Policy](#retention-policy)
9. [Per-Language Client Bindings](#per-language-client-bindings)
10. [Broker-Independence & Abstract Ports](#broker-independence--abstract-ports)
11. [Porting Checklist](#porting-checklist)

---

## Middleware Decision

The event bus for phase-1 integration (联调) and initial production deployment is
**Redis Streams** using XADD for publishing and XREADGROUP consumer groups for
subscription. The architectural decision is recorded in
[ADR-0001](../adr/0001-event-bus-redis-streams.md). This document specifies the
wire contract that all adapters must follow.

---

## Stream Naming

One stream per producing bounded context. Stream key format:

```
events:<context>
```

where `<context>` is the kebab-case service/domain identifier of the **producing**
context.

### All Streams

| # | Producing Context | Stream Key | Produced Event Types (Phase 1) |
|---|---|---|---|
| 1 | `place-network` | `events:place-network` | PlaceRegistered, TransportNodeRegistered |
| 2 | `service-plan` | `events:service-plan` | ServicePlanPublished, ServicePlanChanged |
| 3 | `capacity-availability` | `events:capacity-availability` | CapacityHeld, CapacityHoldConfirmed, CapacityHoldExpired, CapacityReleased, AvailabilityChanged |
| 4 | `fare-pricing` | `events:fare-pricing` | FareRuleSetPublished, FareRuleSetSuperseded |
| 5 | `trip-planning` | `events:trip-planning` | ItineraryProposed |
| 6 | `offer-management` | `events:offer-management` | OfferQuoted, OfferExpired, OfferAccepted, OfferDeclined |
| 7 | `journey-order` | `events:journey-order` | JourneyOrderCreated, JourneyOrderPendingPayment, JourneyOrderConfirmed, JourneyOrderCancelled, JourneyOrderPostSalesAdjusted |
| 8 | `booking-orchestration` | `events:booking-orchestration` | BookingSagaStarted, SegmentReservationRequested, SegmentReservationConfirmed, SegmentReservationFailed, SegmentTicketed, SegmentBookingCancelled |
| 9 | `payment` | `events:payment` | PaymentIntentCreated, PaymentCaptured, PaymentIntentFailed, PaymentExpired, RefundRequested, RefundSettled, RefundFailed |
| 9a | `payment-channel` | `events:payment-channel` | ChannelOrderCreated, ChannelOrderSubmitted, ChannelOrderAccepted, ChannelOrderSucceeded, ChannelOrderFailed, ChannelOrderMissed, ChannelOrderQueryRecorded, ChannelOrderRecoveryDetected, ChannelRefundCreated, ChannelRefundSubmitted, ChannelRefundSucceeded, ChannelRefundFailed, ChannelRefundMissed, ChannelRefundQueryRecorded, ChannelRefundRecoveryDetected, ChannelStatementGenerated, ChannelStatementFrozen, ChannelStatementLineMatched, ReconciliationDiscrepancyOpened, ReconciliationDiscrepancyLinkedToFinanceCase, ReconciliationDiscrepancyResolved |
| 10 | `provider-integration` | `events:provider-integration` | ProviderReservationConfirmed, ProviderReservationFailed, ProviderReservationCancelled, ProviderBoardingAccepted, ProviderSegmentDelayed, ProviderSegmentCancelled |
| 11 | `entitlement-ticketing` | `events:entitlement-ticketing` | EntitlementIssued, EntitlementVoided, EntitlementBoarded, EntitlementSuspended, EntitlementResumed, EntitlementUsed, EntitlementIssueFailed |
| 12 | `fulfillment` | `events:fulfillment` | BoardingVerified, NoShowRecorded, FulfillmentCompleted, EvidenceDisputeOpened, EvidenceDisputeResolved, SegmentArrived, SegmentDelayed, SegmentCancelled |
| 13 | `post-sales` | `events:post-sales` | PostSalesCaseOpened, PostSalesRequested, PostSalesEligibilityEvaluated, PostSalesDecisionQuoted, PostSalesApproved, PostSalesRejected, PostSalesApplied, PostSalesFailed |
| 14 | `notification` | `events:notification` | NotificationScheduled, NotificationDispatched, NotificationDelivered, NotificationFailed, NotificationCancelled |
| 15 | `traveler-profile` | `events:traveler-profile` | TravelerSnapshotUpdated, TravelerDocumentVerified, TravelerEligibilityChanged |
| 16 | `risk-compliance` | `events:risk-compliance` | RiskAssessmentResult, RiskBlockApplied, RiskBlockLifted |
| 17 | `account` | `events:account` | AccountCreated, AccountFrozen, AccountUnfrozen, AccountClosureStarted, AccountClosed, SessionOpened, SessionRevoked, PreferenceUpdated |
| 18 | `admin-audit` | `events:admin-audit` | OperatorRegistered, ManualActionRequested, ManualActionApproved, ManualActionRejected, ManualActionExecuted, AuditEntryRecorded |
| 19 | `customer-service` | `events:customer-service` | SupportCaseOpened, SupportCaseResolved |
| 20 | `finance-settlement` | `events:finance-settlement` | RevenueRecognized, RevenueRecognitionReversed, ReconciliationCompleted, InvoiceGenerated |
| 21 | `reporting` | `events:reporting` | MetricDefined, MetricVersionPublished, ReadModelRebuilt |
| 21b | `legacy-acl` | `events:legacy-acl` | LegacyCommandMapped |
| 22 | `supplier-catalog` | `events:supplier-catalog` | SupplierRegistered, CarrierRegistered, ContractActivated, ContractSuspended, ProductCapabilityDeclared, ExternalCodeMapped |
| 23 | `waitlist` | `events:waitlist` | WaitlistRequestCreated, WaitlistPaymentAuthorizationRequested, WaitlistQueued, WaitlistMatchStarted, WaitlistHoldAuthorized, WaitlistFulfilled, WaitlistCancelled, WaitlistExpired |
| 24 | `dispatch` | `events:dispatch` | DispatchRequested, DriverAssigned, DriverEtaUpdated, DriverArrived, RideStarted, RideEnded, DriverCancelled, DispatchUserCancelled, DispatchNoShowRecorded, DispatchFailed |
| 25 | `wallet-promotion` | `events:wallet-promotion` | BenefitIssued, BenefitReserved, BenefitRedeemed, BenefitReservationReleased, BenefitExpired, BenefitRevoked, BenefitRedemptionReversed |
| 26 | `disruption-recovery` | `events:disruption-recovery` | DisruptionReported, IncidentOpened, RecoveryCaseOpened, RecoveryOptionsGenerated, RecoveryOptionSelected, RecoveryExecutionStarted, RecoveryCompleted, RecoveryFailed, RecoveryCaseClosed, ServiceAlertPublished |
| 27 | `ancillary-service` | `events:ancillary-service` | AncillaryCatalogItemPublished, AncillaryCatalogItemSuspended, AncillaryCatalogItemSuperseded, AncillaryOfferQuoted, AncillaryOfferExpired, AncillaryOrderItemSelected, AncillaryOrderItemPendingConfirmation, AncillaryOrderItemConfirmed, AncillaryOrderItemFulfillmentReady, AncillaryOrderItemFulfilled, AncillaryOrderItemFailed, AncillaryOrderItemCancelled, AncillaryOrderItemRefundPending, AncillaryOrderItemRefunded, AncillaryFulfillmentFactRecorded |
| 28 | `transfer-management` | `events:transfer-management` | TransferPlanCreated, TransferPlanEvaluated, TransferPlanExpired, ConnectionRegistered, TransferRiskEvaluated, TransferAtRisk, ConnectionMissed, ConnectionRecovered, ConnectionRecoveryFailed, ConnectionContractProposed, ConnectionContractConfirmed, ConnectionContractWithdrawn, MctRuleCreated, MctRulePublished, MctRuleRetired |
| 29 | `seat-assignment` | `events:seat-assignment` | SeatMapBuilt, SeatMapBuildFailed, SeatMapVersionPublished, SeatMapVersionRetired, SeatUnitUnavailableMarked, SeatUnitReopened, AdjacencyGroupCreated, AdjacentAllocationSolved, AdjacencyDegradationAccepted, AdjacencyGroupCancelled, BerthPreferenceRecorded, BerthPreferenceApplied, BerthPreferenceCancelled, SeatAllocated, StandingAssigned, SeatAllocationConfirmed, SeatAllocationReleased, SeatAllocationExpired, SeatAllocationFailed, SeatAllocationMissed, SeatReassigned, SeatAllocationLedgerAppended, SeatAllocationCorrectionAppended, SeatAllocationDiscrepancyDetected |
| 30 | `identity-verification` | `events:identity-verification` | CredentialRegistered, VerificationCaseStarted, VerificationSubmittedToSim, VerificationPassed, VerificationFailed, EligibilityCertificateRegistered, EligibilityCertificateVerified, EligibilityUsageReserved, EligibilityUsageConfirmed, EligibilityUsageReleased, PurchaseLimitFactRecorded, PurchaseLimitFactConfirmed, PurchaseLimitFactReleased, PurchaseLimitFactMissed, PurchaseLimitFactFailed |
| 31 | `invoicing` | `events:invoicing` | InvoiceTitleCreated, InvoiceTitleUpdated, DefaultInvoiceTitleSet, InvoiceTitleDeactivated, InvoiceRequested, InvoiceAmountBasisAttached, EInvoiceSubmitted, EInvoiceAccepted, EInvoiceRejected, EInvoiceFailed, EInvoiceIssued, EInvoiceExpired, RedFlushRequested, RedFlushSubmitted, RedFlushAccepted, RedFlushRejected, RedFlushFailed, RedFlushCompleted, RefundWithoutRedFlushObserved |

### Dead-Letter Streams

Each producing context also has a dead-letter stream:

```
events:<context>:dlq
```

Poison messages (messages that have exhausted the retry limit) are moved to the
corresponding DLQ stream. See [Delivery Semantics](#delivery-semantics) for the
poison-handling policy.

---

## Message Format

Every message is stored as a **single-field** Redis Stream entry:

```
XADD events:payment * envelope "<JSON>"
```

The sole field is `envelope`, whose value is the **JSON-serialized EventEnvelope**
as defined in `docs/08-contracts/shared-primitives.md`.

**Rationale for single-field approach:** The entire event schema lives in the
EventEnvelope contract. Redis Stream fields are flat key-value pairs; nesting
the whole envelope as a single JSON string keeps the schema in one place and
avoids field-level schema drift between producer and consumer.

### Worked Example

A `PaymentCaptured` event as stored in the `events:payment` stream:

```
XADD events:payment 1720000000000-0 envelope "{\"eventId\":\"evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222\",\"eventType\":\"PaymentCaptured\",\"schemaVersion\":1,\"producer\":\"payment\",\"causationId\":\"cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555\",\"correlationId\":\"corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444\",\"occurredAt\":\"2026-07-03T10:30:00.000Z\",\"payload\":{\"paymentIntentId\":\"pi-0194f2e0-7b3e-7610-0284-5c26e8b0c789\",\"capturedAmount\":{\"currency\":\"CNY\",\"minorUnits\":35000},\"channel\":\"wechat_pay\",\"channelTransactionId\":\"wx_txn_20260703_a1b2c3\"}}
```

The JSON value (formatted for readability) is:

```json
{
  "eventId": "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222",
  "eventType": "PaymentCaptured",
  "schemaVersion": 1,
  "producer": "payment",
  "causationId": "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555",
  "correlationId": "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444",
  "occurredAt": "2026-07-03T10:30:00.000Z",
  "payload": {
    "paymentIntentId": "pi-0194f2e0-7b3e-7610-0284-5c26e8b0c789",
    "capturedAmount": {
      "currency": "CNY",
      "minorUnits": 35000
    },
    "channel": "wechat_pay",
    "channelTransactionId": "wx_txn_20260703_a1b2c3"
  }
}
```

### Trace Context Propagation (ruling 2026-07-08)

The wire envelope MAY carry two additional OPTIONAL top-level fields:

| Field | Type | Description |
|---|---|---|
| `traceparent` | string | W3C Trace Context `traceparent` of the span active when the envelope was created (i.e. inside the producing transaction, before the outbox relay). |
| `tracestate` | string | W3C Trace Context `tracestate`, only ever present alongside `traceparent`. |

Rules:

- **Additive and optional.** `schemaVersion` stays 1. Absence of these fields
  is never an error; consumers MUST NOT validate their presence or shape as
  part of event-contract conformance (a malformed `traceparent` is ignored,
  never FATAL).
- **Producers** SHOULD populate `traceparent` when OTel tracing is enabled and
  a span context is active at envelope-creation time. The outbox relay
  publishes the stored envelope unchanged, so the trace context is the
  originating request's, not the relay's.
- **Consumers** SHOULD, when tracing is enabled and the field parses, use it
  as the remote parent (or a span link) of the event-consumer span. Handler
  behavior MUST NOT otherwise depend on it.
- `correlationId` remains the business-level end-to-end identifier and is
  unaffected; `traceparent` is observability-only and carries no business
  meaning.

### XADD Command Template

```
XADD events:<context> MAXLEN ~ 100000 * envelope "<JSON>"
```

- `MAXLEN ~ 100000`: Approximate trimming to 100k entries per stream.
- `*`: Let Redis auto-generate the stream entry ID (millisecondsTime-sequence).

---

## Consumer Groups

### Group Naming Convention

```
group = <consuming-context-name>
```

where `<consuming-context-name>` is the kebab-case identifier of the consuming
bounded context (e.g., `journey-order`, `payment`).

### Consumer Naming Convention

```
consumer = <context>-<instance-id>
```

where `<instance-id>` is a pod or instance identifier that is unique within the
consumer group (e.g., `journey-order-pod-0`, `payment-instance-a1b2`).

### Group Creation

Consumer groups are created at deployment time with:

```
XGROUP CREATE events:<context> <group> $ MKSTREAM
```

- `$`: Start consuming only new messages (no historical replay). To replay from
  the beginning, use `0` instead of `$`.
- `MKSTREAM`: Create the stream if it does not already exist.

If the group already exists, the command is a no-op (Redis returns an error that
the consumer group name already exists — this is safe to ignore on restart).

---

## Required Subscriptions

The following table defines which consuming contexts must subscribe to which
streams (derived from the producer→consumer edges documented in
`docs/08-contracts/events/*.md` and the phase-1 contract).

These subscriptions cover the three core business chains (正向购票, 退票, 改签)
plus notification/finance/reporting fan-in.

### Subscription Table

| # | Stream | Consumer Group (Consumer Context) | Rationale |
|---|---|---|---|
| 1 | `events:place-network` | `trip-planning` | Place graph data for itinerary search |
| 2 | `events:service-plan` | `trip-planning` | Schedule changes for itinerary search |
| 3 | `events:capacity-availability` | `trip-planning` | Availability snapshots for itinerary hints |
| 4 | `events:capacity-availability` | `booking-orchestration` | CapacityHeld/CapacityReleased for saga progression |
| 5 | `events:capacity-availability` | `post-sales` | CapacityReleased for refund saga tracking |
| 6 | `events:fare-pricing` | `offer-management` | Fare rule set updates for quote generation |
| 7 | `events:trip-planning` | `offer-management` | ItineraryProposed to trigger quoting |
| 8 | `events:offer-management` | `journey-order` | OfferQuoted/OfferExpired for order creation |
| 9 | `events:journey-order` | `booking-orchestration` | JourneyOrderCreated to start booking saga |
| 10 | `events:journey-order` | `post-sales` | JourneyOrderCancelled for post-sales initiation |
| 11 | `events:journey-order` | `notification` | Order lifecycle events for user notifications |
| 12 | `events:journey-order` | `customer-service` | Order events for support case context |
| 12a | `events:journey-order` | `risk-compliance` | JourneyOrderCreated triggers order risk assessment and blocking decisions |
| 13 | `events:booking-orchestration` | `payment` | SegmentReservationRequested to trigger payment intent creation |
| 14 | `events:booking-orchestration` | `capacity-availability` | SegmentReservationConfirmed to confirm hold |
| 15 | `events:booking-orchestration` | `entitlement-ticketing` | SegmentTicketed for entitlement readiness |
| 16 | `events:booking-orchestration` | `provider-integration` | SegmentReservationRequested for external suppliers |
| 17 | `events:booking-orchestration` | `notification` | Booking saga events for user notifications |
| 18 | `events:payment` | `journey-order` | PaymentCaptured for order status advancement |
| 19 | `events:payment` | `booking-orchestration` | PaymentCaptured to continue saga (issue ticket) |
| 20 | `events:payment` | `finance-settlement` | PaymentCaptured/RefundSettled for reconciliation |
| 20a | `events:payment-channel` | `payment` | ADR-0003 Wave A: Payment consumes terminal channel order/refund facts (`ChannelOrderSucceeded`, `ChannelOrderFailed`, `ChannelOrderMissed`, `ChannelOrderRecoveryDetected`, `ChannelRefundSucceeded`, `ChannelRefundFailed`, `ChannelRefundMissed`, `ChannelRefundRecoveryDetected`) to complete capture/refund handoff. Operational attempt/query facts are ack-skipped by Payment. |
| 20b | `events:payment-channel` | `finance-settlement` | ADR-0003 Wave A: Finance Settlement consumes `ChannelStatementGenerated`, `ChannelStatementFrozen`, and discrepancy/recovery facts for daily SIM channel reconciliation. Non-statement operational facts are ack-skipped unless linked to a reconciliation discrepancy. |
| 21 | `events:payment` | `notification` | Payment events for user notifications |
| 22 | `events:provider-integration` | `booking-orchestration` | ProviderReservationConfirmed for saga progression |
| 23 | `events:provider-integration` | `finance-settlement` | Provider events for settlement |
| 23a | `events:provider-integration` | `disruption-recovery` | ProviderSegmentDelayed and ProviderSegmentCancelled open or merge provider-sourced recovery incidents through segmentRef fan-out. |
| 24 | `events:entitlement-ticketing` | `fulfillment` | EntitlementIssued to prepare for boarding verification |
| 25 | `events:entitlement-ticketing` | `booking-orchestration` | EntitlementIssued to mark segment ticketed |
| 26 | `events:entitlement-ticketing` | `notification` | Ticketing events for user notifications |
| 26a | `events:entitlement-ticketing` | `capacity-availability` | RULING (2026-07-07): EntitlementVoided (payload `references.segmentBookingRef`) releases the matching hold promptly. Closes the refund-applied gap: post-sales flips APPLIED on `CapacityReleased`, which previously only arrived via booking-orchestration's lazy fallback (~90s). Row 30 (release on PostSalesApplied) stays as idempotent backstop. |
| 27 | `events:fulfillment` | `transfer-management` | subscribes only SegmentArrived, SegmentDelayed, SegmentCancelled; all other fulfillment events are explicitly ignored by transfer-management |
| 28 | `events:fulfillment` | `entitlement-ticketing` | BoardingVerified for entitlement lifecycle |
| 28a | `events:fulfillment` | `disruption-recovery` | SegmentDelayed and SegmentCancelled open or merge fulfillment-sourced recovery incidents through segmentRef fan-out. |
| 29 | `events:post-sales` | `payment` | PostSalesApproved to trigger refund |
| 30 | `events:post-sales` | `capacity-availability` | PostSalesApplied to release capacity |
| 31 | `events:post-sales` | `entitlement-ticketing` | PostSalesApproved to void entitlement |
| 32 | `events:post-sales` | `journey-order` | PostSalesApplied to adjust order |
| 33 | `events:post-sales` | `notification` | Post-sales events for user notifications |
| 34 | `events:transfer-management` | `notification` | TransferAtRisk, ConnectionMissed, and ConnectionRecovered produce traveler-facing transfer risk/recovery notification intents; Reporting/Journey Order/Customer Service consumption remains deferred |
| 35 | `events:disruption-recovery` | `notification` | Recovery lifecycle and service-alert facts produce traveler-facing notification intents; Reporting/Journey Order/Customer Service touchpoints remain deferred |
| 36 | `events:notification` | *(none — notification owns its stream)* | Notification events are not consumed by other business contexts in phase 1 |
| 37 | `events:traveler-profile` | `offer-management` | Profile changes for eligibility checks |
| 38 | `events:traveler-profile` | `journey-order` | Profile changes affecting existing orders |
| 39 | `events:risk-compliance` | `journey-order` | Risk block decisions affecting order lifecycle |
| 40 | `events:admin-audit` | *(none — audit trail consumed by admin UI)* | Audit events consumed via read model |
| 41 | `events:finance-settlement` | `reporting` | Settlement events for reporting dashboards |
| 42 | `events:customer-service` | `reporting` | Support case metrics for reporting |
| 43 | `events:reporting` | *(none — reporting events are internal)* | Internal refresh triggers |
| 44 | `events:supplier-catalog` | `provider-integration` | Supplier changes for provider adapter configuration |
| 45 | `events:customer-service` | `admin-audit` | RULING (2026-07-06): ManualActionRequested intake — support-side requests open pending manual actions in Admin & Audit |
| 46 | `events:admin-audit` | `customer-service` | RULING (2026-07-06): ManualActionExecuted/ManualActionRejected outcomes recorded on the support case timeline (ManualActionResultRecorded) |
| 47 | `events:account` | `journey-order` | RULING (2026-07-06): AccountFrozen/AccountUnfrozen/AccountClosed gate order creation — frozen or closed accounts cannot place orders |
| 48 | `events:legacy-acl` | `admin-audit` | RULING (2026-07-07): LegacyCommandMapped recorded as audit entries (DR-014 audit reference) |
| 49 | `events:capacity-availability` | `waitlist` | CapacityReleased starts head-of-queue matching for candidate waitlist requests |
| 50 | `events:journey-order` | `waitlist` | JourneyOrderConfirmed/JourneyOrderCancelled advance a matching request to FULFILLED or return it to QUEUED |
| 51 | `events:waitlist` | `notification` | Waitlist success, failure/requeue, expiry, and cancellation user touchpoints |
| 52 | `events:waitlist` | `reporting` | Waitlist lifecycle metrics and read models |
| 53 | `events:wallet-promotion` | `finance-settlement` | Benefit issuance, redemption, expiry, revocation, and reversal facts for active benefit cost attribution read models |
| 54 | `events:wallet-promotion` | `notification` | Active benefit arrival, expiry, and revocation user touchpoints; BenefitRedeemed and reversal facts are ack-skipped to avoid noisy notifications |
| 55 | `events:wallet-promotion` | `reporting` | Wallet / Promotion lifecycle metrics and read models |
| 56 | `events:post-sales` | `disruption-recovery` | PostSalesApplied converges REFUND recovery execution |
| 56a | `events:journey-order` | `disruption-recovery` | JourneyOrderCreated maintains the local segmentRef-to-order projection used by HTTP reports and provider/fulfillment signal fan-out. |
| 56b | `events:disruption-recovery` | `disruption-recovery` | ServiceAlertPublished rebuilds/repairs the ServiceAlert read model from the normative event payload. |
| 57 | `events:journey-order` | `ancillary-service` | RULING (2026-07-09): JourneyOrderCancelled is the only upstream event consumed by Ancillary Service in this activation wave; it automatically cancels associated non-terminal AncillaryOrderItem records. |
| 58 | `events:disruption-recovery` | `transfer-management` | RecoveryCompleted/RecoveryFailed converge protected missed connections by stored `caseId` mapping. RULING (2026-07-09): for self-executed `REACCOMMODATION`, `RecoveryCompleted` for an already `RECOVERED` connection and matching `caseId` is acknowledged as an idempotent no-op. |
| 59 | `events:capacity-availability` | `seat-assignment` | CapacityReleased and CapacityHoldExpired release or expire matching active seat allocations by `holdId`; Seat Assignment explicitly ignores AvailabilitySnapshot/CapacityHeld/CapacityHoldConfirmed for lifecycle mutation in this wave. |
| 60 | `events:entitlement-ticketing` | `seat-assignment` | EntitlementIssued confirms an active seat allocation; EntitlementVoided and EntitlementIssueFailed release active allocations. EntitlementBoarded/Suspended/Resumed/Used are explicitly ignored by Seat Assignment. |
| 61 | `events:seat-assignment` | `entitlement-ticketing` | SeatAllocated/StandingAssigned/SeatAllocationFailed support issuance-path reconciliation and idempotent retry handling; Entitlement does not consume SeatMap CRUD or ledger/discrepancy events. |
| 62 | `events:seat-assignment` | `booking-orchestration` | Seat allocation lifecycle facts inform saga observability/compensation; Booking Orchestration does not use them to replace Capacity hold/confirm/release commands. |
| 63 | `events:seat-assignment` | `notification` | Seat assignment, standing, reassignment, release, and adjacency degradation user touchpoints; SeatMap CRUD, ledger, and discrepancy events are explicitly ignored. |
| 64 | `events:seat-assignment` | `reporting` | SeatMap, allocation, standing, degradation, release, expiry, ledger, and discrepancy metrics/read models. |
| 65 | `events:traveler-profile` | `identity-verification` | TravelerSnapshotUpdated links traveler snapshots to CredentialRecord read models; Identity Verification does not copy full Traveler Profile master data. |
| 66 | `events:identity-verification` | *(none — downstream consumers deferred)* | ADR-0003 wave A registers identity-verification facts. Journey Order and Fare & Pricing use synchronous HTTP hooks/read queries in this wave. Risk & Compliance consumption of PurchaseLimitFact* and IdentityCluster facts is future-scope and explicitly not active in this wave. |

### Payment Channel subscriptions

`events:payment-channel` is registered for ADR-0003 Wave A. Payment is the only
active platform-money consumer and consumes only terminal order/refund handoff
facts plus recovery facts; it ack-skips statement, line-match, and discrepancy
facts. Finance Settlement consumes daily statement generated/frozen facts and
reconciliation discrepancy/recovery facts for channel reconciliation; it ack-skips
order/refund operational lifecycle facts that are not tied to reconciliation.
Notification, Journey Order, Booking Orchestration, Customer Service, and
Reporting do not receive required Payment Channel subscriptions in this wave; any
future dashboards or customer touchpoints must be added by a later contract
increment rather than inferred from the stream registration.
| 67 | `events:journey-order` | `invoicing` | JourneyOrderConfirmed builds invoice eligibility and read-only itinerary receipt projections; JourneyOrderPostSalesAdjusted updates invoiceable/read-model summaries without mutating issued invoices. |
| 68 | `events:finance-settlement` | `invoicing` | RevenueRecognized, RevenueRecognitionReversed, and InvoiceGenerated provide the authoritative amount basis for blue invoice issuance and red-flush checks. |
| 69 | `events:post-sales` | `invoicing` | PostSalesApproved detects refund intent for local red-flush work; PostSalesApplied is the existing true applied-refund fact used to observe missing red flush; PostSalesFailed cancels unsubmitted local red-flush work when no refund happened. RULING (2026-07-10): Post Sales adds no same-wave pre-refund blocker hook. |
| 70 | `events:admin-audit` | `invoicing` | ManualActionApproved/ManualActionRejected/ManualActionExecuted govern manual invoice and red-flush exception handling. |
| 71 | `events:invoicing` | `notification` | EInvoiceAccepted/EInvoiceRejected/EInvoiceFailed/EInvoiceIssued and red-flush completion/failure facts trigger safe user or enterprise-contact notifications. |
| 72 | `events:invoicing` | `customer-service` | Invoice title, e-invoice, red-flush, and violation facts append safe support timeline entries. |
| 73 | `events:invoicing` | `finance-settlement` | EInvoiceIssued and RedFlushCompleted provide tax-document lifecycle references for finance settlement read models; Finance does not recompute invoice amounts from them. |
| 74 | `events:invoicing` | `admin-audit` | RefundWithoutRedFlushObserved records governance/audit observations for refunds applied before red flush completion. |
| 75 | `events:invoicing` | `reporting` | Invoicing lifecycle, SIM outcome, red-flush timeliness, and violation metrics. |

### Cross-Cutting Consumers

The following consumers subscribe to **all or most** streams for observability,
analytics, and cross-cutting concerns:

| Consumer Group (Context) | Subscribed Streams | Purpose |
|---|---|---|
| `reporting` | All active `events:*` streams except `events:dispatch`, `events:disruption-recovery`, `events:payment-channel`, `events:transfer-management`, and `events:identity-verification` until their deferred-consumer activation waves; includes `events:seat-assignment` and `events:invoicing` in ADR-0003 wave A | Business metrics, funnel analysis, operational dashboards |
| `finance-settlement` | `events:payment`, `events:payment-channel`, `events:provider-integration`, `events:booking-orchestration`, `events:post-sales`, `events:wallet-promotion`, `events:invoicing` | Revenue recognition, reconciliation, invoice generation, Wallet / Promotion benefit-cost attribution, ADR-0003 SIM channel statement reconciliation, and tax-document lifecycle read models. |
| `notification` | `events:journey-order`, `events:booking-orchestration`, `events:payment`, `events:entitlement-ticketing`, `events:post-sales`, `events:wallet-promotion`, `events:disruption-recovery`, `events:transfer-management`, `events:seat-assignment`, `events:invoicing` | User-facing notification triggers including Wallet / Promotion issued/expired/revoked touchpoints, transfer risk/recovery updates, seat/standing/degradation changes, and invoice/red-flush status touchpoints. |

### Invoicing subscriptions and exclusions

`events:invoicing` is registered as an active produced stream in this ADR-0003
Wave-A contract. Active downstream consumers are Notification, Customer Service,
Finance Settlement, Admin & Audit, and Reporting. Post Sales consumption of
`RedFlushCompleted` is explicitly deferred in this wave. Invoicing actively
consumes Journey Order, Finance Settlement, Post Sales, and Admin & Audit streams as listed in
the subscription table. Payment does not consume `events:invoicing` and
Invoicing does not consume `events:payment` in this wave; refund-money authority
remains with Payment, while the existing Post Sales `PostSalesApplied` fact is
the observed applied-refund business fact. No Provider Integration, Capacity,
Entitlement, Fare Pricing, Offer, Traveler Profile, Wallet, Dispatch, Ancillary,
Disruption Recovery, Transfer Management, or Waitlist consumer is added by the
Invoicing contract. 需同波实现: the active subscription rows require Invoicing-side
consumer/producer registration, consumed-event deduplication, red-flush
read-model handlers, and SIM outcome event publishing; they require no same-wave
Post Sales blocking-hook code or consumer registration.

### Seat Assignment subscriptions

`events:seat-assignment` is registered in ADR-0003 wave A. Active consumers are
Entitlement & Ticketing, Booking Orchestration, Notification, and Reporting as
listed in rows 61-64. Entitlement consumes only allocation/standing/failure facts
needed for issuance-path reconciliation; it explicitly excludes SeatMap CRUD,
ledger, and discrepancy facts. Notification consumes user-visible assignment,
standing, reassignment, release, expiry, and adjacency degradation touchpoints; it
explicitly excludes SeatMap CRUD and ledger/discrepancy facts.

Seat Assignment has two active inbound subscriptions in this wave: Capacity &
Availability `CapacityReleased` / `CapacityHoldExpired`, and Entitlement &
Ticketing `EntitlementIssued` / `EntitlementVoided` / `EntitlementIssueFailed`.
It explicitly does not consume Waitlist, Dispatch, Wallet / Promotion, Disruption
Recovery, Transfer Management, or Provider Integration streams in this wave. SIM
composition behavior is deterministic and seed-driven through operations HTTP, not
through a provider stream.

### Disruption Recovery subscriptions

`events:disruption-recovery` is registered as a produced stream in this
contract. Notification actively consumes traveler-facing recovery lifecycle and
service-alert facts; Reporting, Journey Order, and Customer Service consumption
remain deferred in this activation wave. Disruption Recovery actively consumes
`PostSalesApplied` from `events:post-sales` to converge selected `REFUND`
recovery options, `JourneyOrderCreated` from `events:journey-order` to maintain
the local `segmentRef` fan-out projection, `SegmentDelayed`/`SegmentCancelled`
from `events:fulfillment`, and `ProviderSegmentDelayed`/`ProviderSegmentCancelled`
from `events:provider-integration` as disruption sources. It also consumes its
own `ServiceAlertPublished` facts to rebuild/repair the ServiceAlert read model.
Provider Integration and Fulfillment segment signals that arrive before the local
Journey Order projection are retained for retry instead of ack-skipped. Service
Plan signal sources remain deferred. Transfer Management opens protected
missed-connection cases through Disruption Recovery HTTP and actively consumes
`RecoveryCompleted` and `RecoveryFailed` from `events:disruption-recovery` by
stored `caseId`.
Disruption Recovery executes scoped `REACCOMMODATION` through Transfer Management
HTTP (`POST /api/v1/connections/{connectionId}/reaccommodate`); this does not add
a new subscription row. The resulting `ConnectionRecovered` event carries
`replacementConnectionId` and replacement-window fields rather than introducing a
new `ConnectionReaccommodated` event.

### Deferred Identity Verification Subscriptions

`events:identity-verification` is registered as a produced stream in this contract. In ADR-0003 wave A there is no active downstream consumer group for this stream: Journey Order uses `POST /api/v1/identity-verification/pre-order-checks`, Fare & Pricing uses the read-only eligibility-certificate query, and Risk & Compliance consumption of `PurchaseLimitFactRecorded`, `PurchaseLimitFactConfirmed`, `PurchaseLimitFactReleased`, `PurchaseLimitFactMissed`, and `PurchaseLimitFactFailed` is future-scope. Reporting and Customer Service projections are likewise deferred unless a later wave activates their consumers.

Identity Verification has one active inbound subscription in this wave: it consumes `TravelerSnapshotUpdated` from `events:traveler-profile` for traveler linkage and masked document references. Optional annotation from `RiskBlockApplied`/`RiskBlockLifted` remains a documented future extension and does not create a required subscription row in this wave.

### Deferred Ancillary Service Subscriptions

`events:ancillary-service` is registered as a produced stream in this contract.
Ancillary Service actively consumes only `JourneyOrderCancelled` from
`events:journey-order` in this activation wave. Finance Settlement, Notification,
and Post Sales are documented downstream touchpoints for ancillary order-item,
refund, and fulfillment-fact events, but their concrete consumer implementations
are deferred. Journey Order contract shapes are not changed by this activation;
any order-detail projection of ancillary items is future work.

### Deferred Dispatch Subscriptions

`events:dispatch` is registered as a produced stream in this contract, but has no
required consumer groups in this activation wave. Immediate driver/vehicle/ETA
supply is simulated through internal Dispatch ops HTTP commands, so
`provider-integration` does not subscribe or publish Dispatch provider events.
Fulfillment handoff consumption of `DriverArrived`, `RideStarted`, and
`RideEnded` is deferred; Post Sales, Notification, and Reporting subscriptions to
Dispatch lifecycle events are likewise deferred until their implementation waves.

---

## Delivery Semantics

### At-Least-Once

Redis Streams consumer groups provide **at-least-once** delivery. A message is
considered delivered when it is returned by XREADGROUP to a consumer. The
consumer must **XACK** the message after successful local processing.

```
XREADGROUP GROUP <group> <consumer> BLOCK 2000 COUNT 10 STREAMS events:<stream> >
# ... process messages ...
XACK events:<stream> <group> <entry-id>
```

### Processing Flow

1. **Poll:** Consumer calls `XREADGROUP` with `BLOCK` to wait for new messages.
2. **Process:** Consumer deserialises the `envelope` JSON, validates the event,
   and dispatches it to the domain handler.
3. **Ack:** On successful processing, the consumer calls `XACK`.
4. **Fail:** On transient failure, the consumer does NOT XACK. The message
   remains in the Pending Entries List (PEL).
5. **Recovery:** After `min-idle` timeout (60s), another consumer or a recovery
   process picks up the pending message via `XAUTOCLAIM`.
6. **Poison:** If a message has been retried 5 times (tracked via delivery
   count in PEL), it is moved to the dead-letter stream
   `events:<context>:dlq` and XACKed from the main stream.

### Deduplication

Consumers MUST deduplicate by `eventId` (from the EventEnvelope). Each consumer
context maintains a **consumed-event log** that records processed `eventId`
values. Before processing any event, the consumer checks whether the `eventId`
has already been processed.

- **Finance-settlement** and **reporting** already model this pattern.
- **All other contexts** follow the same pattern: a lightweight dedup table
  (in-process LRU cache + persistent store, or a dedicated dedup collection in
  the service database) keyed by `eventId`.

```pseudo
def handle_event(envelope):
    if dedup_log.exists(envelope.eventId):
        XACK(...)  # already processed, ack and skip
        return
    try:
        domain_handler(envelope)
        dedup_log.record(envelope.eventId)
        XACK(...)
    except TransientError:
        # do not XACK; let XAUTOCLAIM retry
        raise
    except FatalError:
        # move to DLQ and XACK
        XADD events:<context>:dlq * envelope "..."
        XACK(...)
```

### Poison Message Handling

```
XAUTOCLAIM events:<context> <group> <consumer> 60000 0 COUNT 100
```

- `min-idle`: 60000 ms (60 seconds).
- For each claimed message, check the delivery count (from the PEL entry).
- If delivery count >= 5: `XADD events:<context>:dlq * envelope "<JSON>"` then
  `XACK events:<context> <group> <entry-id>`.
- If delivery count < 5: attempt reprocessing.

---

## Ordering Guarantees

| Scope | Ordering Guarantee |
|---|---|
| **Within a single stream** | Ordered by Redis stream entry ID (chronological within millisecond). |
| **Across different streams** | **No ordering guarantee.** Consumers that process events from multiple streams (e.g., booking-orchestration consuming from `events:journey-order` and `events:payment`) must NOT assume a global order. |
| **Saga correlation** | Sagas correlate via `correlationId` / `causationId` in the EventEnvelope, not by stream order. |

Redis Streams guarantees that entries in a single stream are delivered to
consumers in the order they were added. However, when a consumer group has
multiple consumers, each consumer receives a subset of the stream; ordering is
preserved per consumer but not across consumers in the same group.

---

## Retention Policy

```
XADD events:<context> MAXLEN ~ 100000 * envelope "<JSON>"
```

- `MAXLEN ~ 100000`: Each stream is capped at approximately 100,000 entries.
  Redis uses an efficient eviction strategy (`~` approximate trimming) that
  reclaims memory in batches rather than on every write.
- **Phase-1 scope:** This cap is sufficient for the integration-testing and
  initial production workload. For production scale-out, the retention policy
  will be revisited (possibly time-based TTL via `XTRIM` or a separate
  archival process).
- **DLQ streams:** Dead-letter streams (`events:<context>:dlq`) have the same
  `MAXLEN ~ 100000` cap. Poison messages that require investigation should be
  consumed from the DLQ by an operational tool before they are trimmed.

---

## Per-Language Client Bindings

All implementation tasks MUST use the following Redis client libraries. The
table lists the API methods used for each operation.

| Language | Client Library | XADD | XREADGROUP | XACK | XAUTOCLAIM | XGROUP CREATE | XTRIM / XLEN |
|---|---|---|---|---|---|---|---|
| **Java** | [Lettuce](https://github.com/redis/lettuce) 6.x | `redis.xadd(args)` | `redis.xreadgroup(args)` | `redis.xack(args)` | `redis.xautoclaim(args)` | `redis.xgroupCreate(args)` | `redis.xtrim(args)` / `redis.xlen(args)` |
| **Go** | [go-redis/v9](https://github.com/redis/go-redis) | `client.XAdd(ctx, &XAddArgs{...})` | `client.XReadGroup(ctx, &XReadGroupArgs{...})` | `client.XAck(ctx, stream, group, ids...)` | `client.XAutoClaim(ctx, &XAutoClaimArgs{...})` | `client.XGroupCreate(ctx, stream, group, start)` | `client.XTrimMaxLen(ctx, stream, maxLen)` / `client.XLen(ctx, stream)` |
| **Python** | [redis-py](https://github.com/redis/redis-py) 5.x | `r.xadd(name, fields)` | `r.xreadgroup(group, consumer, streams)` | `r.xack(name, group, *ids)` | `r.xautoclaim(name, group, consumer, min_idle_time, start_id)` | `r.xgroup_create(name, group, id="$", mkstream=True)` | `r.xtrim(name, maxlen=100000)` / `r.xlen(name)` |
| **Rust** | [redis-rs](https://github.com/redis-rs/redis-rs) 0.25+ | `cmd("XADD").arg(name).arg(fields).query_async(con)` | `cmd("XREADGROUP").arg(group).arg(consumer).arg(streams).query_async(con)` | `cmd("XACK").arg(name).arg(group).arg(id).query_async(con)` | `cmd("XAUTOCLAIM").arg(name).arg(group).arg(consumer).arg(min_idle).arg(start).query_async(con)` | `cmd("XGROUP").arg("CREATE").arg(name).arg(group).arg("$").arg("MKSTREAM").query_async(con)` | `cmd("XTRIM").arg(name).arg("MAXLEN").arg(maxlen).query_async(con)` / `cmd("XLEN").arg(name).query_async(con)` |
| **TypeScript** | [ioredis](https://github.com/redis/ioredis) 5.x | `redis.xadd(name, '*', field, value)` | `redis.xreadgroup('GROUP', group, consumer, 'BLOCK', ms, 'COUNT', n, 'STREAMS', ...streams)` | `redis.xack(name, group, ...ids)` | `redis.xautoclaim(name, group, consumer, minIdleTime, startId)` | `redis.xgroup('CREATE', name, group, '$', 'MKSTREAM')` | `redis.xtrim(name, 'MAXLEN', '~', maxlen)` / `redis.xlen(name)` |

---

## Broker-Independence & Abstract Ports

Every service MUST code against abstract ports — NOT against Redis types or
stream-key strings. This section defines the language-neutral interface
semantics that all adapters implement.

### Abstract Port: `EventPublisher`

```
interface EventPublisher {
    /**
     * Publish a domain event to the event bus.
     *
     * @param envelope  The fully-populated EventEnvelope (eventId, eventType,
     *                  occurredAt, correlationId, causationId, producer,
     *                  schemaVersion, payload — per shared-primitives.md §1).
     *
     * Behavior:
     * - The implementation MUST determine the target stream from the `producer`
     *   field of the envelope (stream = "events:<producer>").
     * - The implementation MUST serialize the entire envelope as a single JSON
     *   value in the "envelope" field of the Redis Stream entry (or the
     *   equivalent single-message field in the target broker).
     * - The implementation MUST apply the retention policy (MAXLEN ~ 100000)
     *   on publish.
     * - On transient failure (connection loss, timeout), the implementation
     *   MUST retry with exponential backoff (3 attempts) before propagating
     *   the error to the caller.
     * - On persistent failure, the implementation MUST throw/return a
     *   PublishFailed error.
     *
     * Error behavior:
     * - PublishFailed: The event was not published. The caller (domain service)
     *   must handle this by either retrying later or recording the event for
     *   outbox-based delivery.
     *
     * Thread safety:
     * - Implementations MUST be thread-safe. Multiple domain aggregates may
     *   publish events concurrently.
     */
    publish(envelope: EventEnvelope): Result<void, PublishFailed>
}
```

### Abstract Port: `EventSubscriber`

```
interface EventSubscriber {
    /**
     * Subscribe to one or more event streams as a consumer group member.
     *
     * @param streams       List of stream keys to subscribe to (e.g.,
     *                      ["events:payment", "events:booking-orchestration"]).
     * @param group         The consumer group name (must equal the consuming
     *                      context name, e.g., "journey-order").
     * @param consumerName  The unique consumer instance identifier within the
     *                      group (e.g., "journey-order-pod-0").
     * @param handler       A callback that receives each EventEnvelope and
     *                      returns a Result indicating success or failure.
     *
     * Behavior:
     * - The implementation MUST create the consumer group on startup if it
     *   does not exist (XGROUP CREATE ... MKSTREAM).
     * - The implementation MUST poll each stream in a loop using XREADGROUP
     *   (or equivalent consumer-group read in the target broker).
     * - For each received message, the implementation MUST:
     *     1. Deserialize the "envelope" field into an EventEnvelope.
     *     2. Call the handler with the deserialized envelope.
     *     3. If the handler returns success -> XACK the message.
     *     4. If the handler returns a transient error -> do NOT XACK (let
     *        XAUTOCLAIM recover the message).
     *     5. If the handler returns a fatal error -> move to DLQ and XACK.
     * - The implementation MUST track delivery attempts per message.
     *   After 5 delivery attempts (as observed from PEL delivery count),
     *   move the message to the dead-letter stream and XACK.
     * - The implementation MUST call XAUTOCLAIM periodically (every 60s)
     *   to recover messages in the PEL that belong to other consumers.
     *
     * At-least-once + dedup obligations (handler side):
     * - The handler (domain/application layer) MUST deduplicate by eventId.
     * - The subscriber adapter MUST provide the handler with the raw
     *   EventEnvelope so that the handler can inspect eventId.
     * - The adapter MAY provide a convenience filter that drops already-seen
     *   eventIds before calling the handler, but the handler MUST still be
     *   idempotent.
     *
     * Error behavior:
     * - SubscribeFailed: The subscriber could not start (e.g., Redis
     *   connection failed). The service should fail fast and let the
     *   orchestrator restart the pod.
     *
     * Lifecycle:
     * - The subscriber runs in the background (separate thread / async task).
     * - The service MUST provide a graceful shutdown mechanism that stops
     *   polling and completes in-flight processing before exit.
     */
    subscribe(streams: List<String>, group: String,
              consumerName: String,
              handler: (EventEnvelope) -> Result<Success, HandlerError>): Result<Void, SubscribeFailed>
}
```

### Port Implementation Rules

**(a) Domain and application layers depend ONLY on these ports.**

No Redis type, import, or stream-key string may appear outside one adapter
module per service. The canonical location for adapter modules is:

```
<service>/**/adapters/messaging/
```

For example:
- `services/journey-order/src/main/java/com/trainticket/journeyorder/adapters/messaging/`
- `services/payment/internal/adapters/messaging/`
- `services/notification/src/adapters/messaging/`

**(b) Stream naming, consumer-group naming, retry and DLQ policy live in the
adapter layer + this contract, never in domain code.**

Domain code calls `EventPublisher.publish(envelope)` and receives events via
`EventSubscriber` callbacks. It does not know whether the implementation uses
Redis Streams, Kafka, or an in-memory bus.

**(c) Porting checklist.**

See the [Porting Checklist](#porting-checklist) section below, which maps every
port concept to Kafka/Redpanda bindings to prove the port surface is
broker-neutral.

---

## Porting Checklist

This checklist maps every Redis-Streams-specific concept in the contract to a
Kafka/Redpanda equivalent. If any port method or contract concept cannot be
mapped, the port is wrong and must be redesigned.

| Redis Streams Concept | Kafka / Redpanda Equivalent | Notes |
|---|---|---|
| **Stream:** `events:<context>` | **Topic:** `events.<context>` | Kafka topics are the closest equivalent to Redis Streams. Naming convention changes from colon-separated to dot-separated (Kafka best practice). |
| **Message field:** single `envelope` JSON | **Message value:** single JSON `envelope` | Identical serialization. The same EventEnvelope JSON is used as the Kafka message value. |
| **Consumer group:** `XGROUP CREATE` | **Consumer group:** `KafkaConsumer.subscribe()` with `group.id` | Kafka consumer groups provide the same competing-consumer semantics. |
| **Consumer name:** unique per instance | **Consumer instance:** `client.id` + partition assignment | Kafka assigns partitions to consumers within a group. Each instance processes a subset of partitions. |
| **XADD:** append entry | **producer.send():** produce to topic | Equivalent: both append to an ordered log. |
| **XREADGROUP:** poll for new messages | **consumer.poll():** poll for new records | Both block or poll for new messages. |
| **XACK:** acknowledge processed message | **offset commit:** `commitSync()` / `commitAsync()` | XACK removes from PEL; offset commit marks position in partition. |
| **PEL (Pending Entries List)** | **Uncommitted offsets:** tracked by consumer group coordinator | Messages between last committed offset and latest offset are "pending" (uncommitted). |
| **XAUTOCLAIM:** reassign stale pending messages | **Rebalance + `seek()`:** on rebalance, partitions are reassigned; consumer processes from last committed offset | XAUTOCLAIM is a Redis-specific recovery mechanism. In Kafka, rebalance automatically reassigns partitions; the new consumer resumes from the last committed offset. No explicit claim command needed. |
| **Delivery count tracking** | **Header-based:** record delivery count in message header; consumer checks and increments | Kafka does not natively track delivery count per message. Implement via a custom header (`delivery-count`) set by the producer or by the consumer on retry. |
| **Dead-letter stream:** `events:<context>:dlq` | **Dead-letter topic:** `events.<context>.dlq` | Produce poison messages to a separate DLQ topic. |
| **MAXLEN ~ 100000:** capped retention | **log.retention.bytes / log.retention.ms:** time or size-based retention | Kafka uses configurable retention policies (time, size, or compacted). Replace `MAXLEN` with `retention.ms=604800000` (7 days) or `retention.bytes=1073741824` (1 GB). |
| **XRANGE:** replay historical entries | **consumer.seek() + poll():** seek to an earlier offset and consume | Both support replay. XRANGE is an explicit range query; Kafka uses offset seeking. |
| **`$` (new messages only):** start from tail | **`auto.offset.reset=latest`:** start consuming from the end of the topic | Equivalent: skip historical messages and consume only new ones. |
| **`0` (from beginning):** replay all | **`auto.offset.reset=earliest`:** start consuming from the beginning of the topic | Equivalent: consume all messages from the start. |
| **Blocking read:** `BLOCK 2000` | **`max.poll.interval.ms` + `fetch.max.wait.ms`:** long-poll with timeout | Both support long-polling with configurable timeout. |

### Porting Validation

When porting to a new broker backend, follow these steps:

1. Implement `EventPublisher` and `EventSubscriber` using the new broker's
   client library.
2. Map every Redis-specific concept in the table above to the equivalent
   broker concept.
3. Verify that no Redis Streams API call, import, or stream-key string appears
   in the adapter's public interface.
4. Verify that domain/application code does not import any broker-specific
   types.
5. Run the existing integration tests with the new adapter.

If any of the abstract port methods (`publish`, `subscribe`) cannot be
implemented with the target broker, the port surface is not broker-neutral and
must be redesigned in this document, not in the implementation task.

---

## Appendices

### A. Consumer Group Lifecycle

```
Startup:
  for each required stream:
    XGROUP CREATE events:<context> <group> $ MKSTREAM   (ignore "BUSYGROUP" error)

Polling loop:
  loop:
    messages = XREADGROUP GROUP <group> <consumer> BLOCK 2000 COUNT 10 STREAMS events:<stream> >
    for each message in messages:
      envelope = JSON.parse(message["envelope"])
      result = handler(envelope)
      if result.is_ok():
        XACK events:<stream> <group> message.id
      elif result.is_transient():
        continue  # leave in PEL for retry
      else:
        # fatal error: move to DLQ
        XADD events:<stream>:dlq * envelope message["envelope"]
        XACK events:<stream> <group> message.id

Recovery loop (every 60s):
    claimed = XAUTOCLAIM events:<stream> <group> <consumer> 60000 "0" COUNT 100
    for each claimed message:
      if message.delivery_count >= 5:
        XADD events:<stream>:dlq * envelope message["envelope"]
        XACK events:<stream> <group> message.id
      else:
        envelope = JSON.parse(message["envelope"])
        result = handler(envelope)
        # ... same ack/dlq logic as above ...
```

### B. Required Files per Service

Every service that publishes or subscribes to events MUST include:

```
<service-root>/
  src/
    adapters/
      messaging/
        publisher.ts          # EventPublisher implementation (or .java, .go, .py, .rs)
        subscriber.ts         # EventSubscriber implementation
        stream-config.ts      # Stream names, group names, consumer naming
        dlq-handler.ts        # Poison message handling
```

For single-language services with flat layouts, the pattern adapts accordingly:

```
services/payment/
  internal/
    adapters/
      messaging/
        publisher.go
        subscriber.go
        stream_config.go
        dlq_handler.go
```
