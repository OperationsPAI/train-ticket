# Entitlement & Ticketing — Events & Commands

Last updated: 2026-07-04

## Published Events

### EntitlementIssued

| Field | Description |
|---|---|
| **Producer** | entitlement-ticketing |
| **Consumers** | booking-orchestration, journey-order, notification, fulfillment |
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

### EntitlementVoided

| Field | Description |
|---|---|
| **Producer** | entitlement-ticketing |
| **Consumers** | booking-orchestration, notification, post-sales, capacity-availability |
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
| **Consumers** | booking-orchestration |
| **Trigger** | Entitlement issuance failed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Entitlement ID (`ent-<uuid>`). |
| `retryable` | boolean | yes | Whether the failure can be retried. |
| `failureCode` | string | yes | Machine-readable failure code. |
| `failureMessage` | string | no | Human-readable failure description. |
| `failedAt` | RFC3339 UTC | yes | Failure timestamp. |

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
