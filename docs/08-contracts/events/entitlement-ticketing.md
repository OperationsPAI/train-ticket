# Entitlement & Ticketing — Events & Commands

Last updated: 2026-07-09

## Published Events

### EntitlementIssued

| Field | Description |
|---|---|
| **Producer** | entitlement-ticketing |
| **Consumers** | booking-orchestration, journey-order, notification, fulfillment, seat-assignment |
| **Trigger** | `IssueEntitlement` command processed; ticket/credential generated. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Canonical entitlement ID (`ent-<uuid>`). |
| `segmentBookingId` | `SegmentBookingId` | yes | Reference to the segment booking (`sb-<uuid>`). |
| `journeyOrderId` | `OrderId` | yes | Reference to the journey order (`ord-<uuid>`). |
| `travelerRef` | `TravelerId` | yes | Traveler reference (`tvl-<uuid>`). |
| `segmentRef` | `SegmentRef` | yes | Segment reference (`seg-<uuid>`). |
| `issuePurpose` | string | yes | Purpose of issuance: `INITIAL`, `REPLACEMENT`, `MANUAL_RECOVERY`, `PROVIDER_REBUILD`, `DISRUPTION_REPLACEMENT`. |
| `credentialNo` | string | yes | Unique credential/ticket number. |
| `credentialType` | string | yes | Type of credential: `E_TICKET`, `PAPER_TICKET`, `PICKUP_CODE`, `BOARDING_PASS`, `FERRY_TICKET`, `COACH_E_TICKET`, `RIDE_CODE`. |
| `issuedAt` | RFC3339 UTC | yes | Issue timestamp. |
| `seatAllocationId` | string | no | Seat Assignment allocation ID (`salloc-<uuid>`). Needs same-wave implementation; required for train/seat-assigned issuance paths. |
| `seatRef` | object | no | Seat Assignment `SeatRef` display fact. Needs same-wave implementation; required when `seatAllocationId` is present and may carry `allocationType=STANDING`. |

### EntitlementVoided

| Field | Description |
|---|---|
| **Producer** | entitlement-ticketing |
| **Consumers** | booking-orchestration, notification, post-sales, capacity-availability, seat-assignment |
| **Trigger** | `VoidEntitlement` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Entitlement ID (`ent-<uuid>`). |
| `segmentBookingId` | `SegmentBookingId` | yes | Reference to the segment booking (`sb-<uuid>`). |
| `voidedAt` | RFC3339 UTC | yes | Void timestamp. |
| `reason` | string | yes | Void reason: `REFUND`, `CHANGE`, `DISRUPTION`, `RISK`, `MANUAL_CORRECTION`. |
| `policy` | string | yes | Void policy: `NORMAL` or `EXCEPTIONAL_RULE`. |
| `businessCaseRef` | string | no | Reference to the post-sales or recovery case that authorised the void. |
| `seatAllocationId` | string | no | Seat Assignment allocation ID (`salloc-<uuid>`) when the entitlement carries one; needs same-wave implementation for prompt release. |

### EntitlementBoarded

| Field | Description |
|---|---|
| **Producer** | entitlement-ticketing |
| **Consumers** | fulfillment, journey-order |
| **Trigger** | Passenger boarded the service. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Entitlement ID (`ent-<uuid>`). |
| `boardedAt` | RFC3339 UTC | yes | Boarding timestamp. |

### EntitlementSuspended

| Field | Description |
|---|---|
| **Producer** | entitlement-ticketing |
| **Consumers** | post-sales, notification, fulfillment |
| **Trigger** | Entitlement suspended due to dispute, risk, or provider conflict. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Entitlement ID (`ent-<uuid>`). |
| `suspendedAt` | RFC3339 UTC | yes | Suspension timestamp. |
| `reason` | string | yes | Suspension reason: `RISK`, `PROVIDER_CONFLICT`, `MANUAL_REVIEW`, `DISRUPTION`, `ISSUED_AFTER_REFUND_REQUESTED`. |
| `businessCaseRef` | string | no | Reference to the business case that initiated the suspension. |

### EntitlementResumed

| Field | Description |
|---|---|
| **Producer** | entitlement-ticketing |
| **Consumers** | notification, fulfillment |
| **Trigger** | `ResumeEntitlement` command processed; suspension cleared. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Entitlement ID (`ent-<uuid>`). |
| `resumedAt` | RFC3339 UTC | yes | Resume timestamp. |
| `reason` | string | yes | Resume reason: `RISK_CLEARED`, `PROVIDER_CONFLICT_RESOLVED`, `MANUAL_APPROVED`. |
| `businessCaseRef` | string | no | Reference to the business case that authorised the resume. |

### EntitlementUsed

| Field | Description |
|---|---|
| **Producer** | entitlement-ticketing |
| **Consumers** | journey-order, booking-orchestration, notification |
| **Trigger** | Segment completed; entitlement marked as used. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Entitlement ID (`ent-<uuid>`). |
| `usedAt` | RFC3339 UTC | yes | Usage completion timestamp. |
| `fulfillmentFactRef` | string | yes | Reference to the fulfillment fact that triggered the used transition. |

### EntitlementIssueFailed

| Field | Description |
|---|---|
| **Producer** | entitlement-ticketing |
| **Consumers** | booking-orchestration, seat-assignment |
| **Trigger** | Entitlement issuance failed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Entitlement ID (`ent-<uuid>`). |
| `retryable` | boolean | yes | Whether the failure can be retried. |
| `failureCode` | string | yes | Machine-readable failure code. |
| `failureMessage` | string | no | Human-readable failure description. |
| `failedAt` | RFC3339 UTC | yes | Failure timestamp. |
| `segmentBookingId` | `SegmentBookingId` | no | Segment booking ID when available; needs same-wave implementation so Seat Assignment can release an unconfirmed allocation. |
| `seatAllocationId` | string | no | Seat Assignment allocation ID (`salloc-<uuid>`) when allocation already happened; needs same-wave implementation. |


## Seat Assignment increment — needs same-wave implementation

ADR-0003 wave A requires Entitlement & Ticketing code changes in the same wave as
this contract increment. The implementation must update the IssueEntitlement
command schema, validators, issuance application service, Seat Assignment HTTP
client adapter, credential persistence/projection, and outbox/event serializers.
The relevant enum validation surface is `allocationType`, `berthPosition`,
`seatPosition`, `adjacencyPreference`, and `degradationReason`.

## Accepted Commands

### IssueEntitlement

| Field | Description |
|---|---|
| **Sender** | booking-orchestration |
| **Trigger** | All issue preconditions satisfied: booking confirmed, payment captured, capacity committed, traveler accepted, risk allowed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `idempotencyKey` | string | yes | Idempotency key combining `segmentBookingId + travelerId + issuePurpose`. |
| `preconditions` | object | yes | Accepted facts from booking, capacity, payment, traveler, and risk contexts. |
| `credentialNo` | string | yes | Unique credential/ticket number. |
| `credentialType` | string | yes | Type of credential. |
| `providerRef` | string | no | Provider reference if externally-issued credential. |
| `providerConfirmationNo` | string | no | Provider confirmation number. |
| `seatPreferences` | object | no | Seat Assignment `SeatPreferences` from issue request. Needs same-wave implementation and validation before Seat Assignment call. |
| `seatAllocationId` | string | no | Seat Assignment allocation ID returned by the issuance-path call; required before committing a seat-assigned credential. |
| `seatRef` | object | no | Seat Assignment `SeatRef` returned by the issuance-path call; persisted on the credential and emitted with `EntitlementIssued`. |

### VoidEntitlement

| Field | Description |
|---|---|
| **Sender** | post-sales, disruption-recovery |
| **Trigger** | Post-sales case approved or disruption recovery initiated. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Entitlement ID (`ent-<uuid>`). |
| `reason` | string | yes | Void reason. |
| `policy` | string | yes | `NORMAL` or `EXCEPTIONAL_RULE`. |
| `businessCaseRef` | string | no | Reference to the authorising case. |

### SuspendEntitlement

| Field | Description |
|---|---|
| **Sender** | risk-compliance, post-sales, disruption-recovery |
| **Trigger** | Risk, dispute, or provider conflict detected. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Entitlement ID (`ent-<uuid>`). |
| `reason` | string | yes | Suspension reason. |
| `businessCaseRef` | string | yes | Reference to the business case. |

### ResumeEntitlement

| Field | Description |
|---|---|
| **Sender** | risk-compliance, post-sales, customer-service |
| **Trigger** | Suspension cause resolved. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Entitlement ID (`ent-<uuid>`). |
| `reason` | string | yes | Resume reason. |
| `businessCaseRef` | string | yes | Reference to the business case. |

### BindProviderCredential

| Field | Description |
|---|---|
| **Sender** | provider-integration |
| **Trigger** | Provider returns ticket/credential result. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `credentialId` | string | yes | Credential identifier. |
| `providerRef` | `ProviderRef` | yes | Provider reference (`prv-<uuid>`). |
| `confirmationNo` | string | yes | Provider confirmation number. |
| `ticketNo` | string | yes | Provider ticket number. |
| `mappedStatus` | string | yes | Platform-mapped status. |
