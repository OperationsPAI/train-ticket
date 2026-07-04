# Fulfillment — Events & Commands

Last updated: 2026-07-05

## Published Events

### BoardingVerified

| Field | Description |
|---|---|
| **Producer** | fulfillment |
| **Consumers** | entitlement-ticketing, journey-order, notification |
| **Trigger** | `VerifyBoarding` command processed; passenger boarding verified at gate/station. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `fulfillmentRecordId` | string | yes | Fulfillment record identifier. Format: `fr-<uuid>`. |
| `entitlementId` | `EntitlementId` | yes | Reference to the entitlement (`ent-<uuid>`). |
| `segmentBookingId` | `SegmentBookingId` | yes | Reference to the segment booking (`sb-<uuid>`). |
| `journeyOrderId` | `OrderId` | yes | Reference to the journey order (`ord-<uuid>`). |
| `travelerId` | `TravelerId` | yes | Traveler reference (`tvl-<uuid>`). |
| `segmentRef` | `SegmentRef` | yes | Segment reference (`seg-<uuid>`). |
| `source` | string | yes | Source of the boarding verification: `GATE`, `STATION`, `PROVIDER`, `CONDUCTOR`, `ADMIN`. |
| `sourceEventId` | string | yes | Source event identifier for idempotency. |
| `occurredAt` | RFC3339 UTC | yes | When the boarding event physically occurred. |
| `receivedAt` | RFC3339 UTC | yes | When the boarding event was received by the system. |
| `locationSnapshot` | object | no | Location snapshot: `{ "placeId": "plc-<uuid>", "nodeId": "tnd-<uuid>", "displayName": "..." }`. |

### NoShowRecorded

| Field | Description |
|---|---|
| **Producer** | fulfillment |
| **Consumers** | entitlement-ticketing, journey-order, post-sales, notification |
| **Trigger** | `RecordNoShow` command processed; passenger did not board within the boarding window. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `fulfillmentRecordId` | string | yes | Fulfillment record identifier (`fr-<uuid>`). |
| `entitlementId` | `EntitlementId` | yes | Reference to the entitlement (`ent-<uuid>`). |
| `segmentBookingId` | `SegmentBookingId` | yes | Reference to the segment booking (`sb-<uuid>`). |
| `journeyOrderId` | `OrderId` | yes | Reference to the journey order (`ord-<uuid>`). |
| `travelerId` | `TravelerId` | yes | Traveler reference (`tvl-<uuid>`). |
| `segmentRef` | `SegmentRef` | yes | Segment reference (`seg-<uuid>`). |
| `reason` | string | yes | No-show reason: `BOARDING_WINDOW_EXPIRED`, `VERIFICATION_FAILED`, `MANUAL_RECORD`. |
| `assessedAt` | RFC3339 UTC | yes | When the no-show was assessed. |

### FulfillmentCompleted

| Field | Description |
|---|---|
| **Producer** | fulfillment |
| **Consumers** | entitlement-ticketing, journey-order, notification, finance-settlement |
| **Trigger** | Segment fulfillment completed (arrival confirmed or segment finished). |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `fulfillmentRecordId` | string | yes | Fulfillment record identifier (`fr-<uuid>`). |
| `entitlementId` | `EntitlementId` | yes | Reference to the entitlement (`ent-<uuid>`). |
| `segmentBookingId` | `SegmentBookingId` | yes | Reference to the segment booking (`sb-<uuid>`). |
| `journeyOrderId` | `OrderId` | yes | Reference to the journey order (`ord-<uuid>`). |
| `travelerId` | `TravelerId` | yes | Traveler reference (`tvl-<uuid>`). |
| `completedAt` | RFC3339 UTC | yes | When the fulfillment was completed. |
| `completionSource` | string | yes | Source of completion: `ARRIVAL`, `PROVIDER`, `ADMIN`, `SYSTEM`. |

### EvidenceDisputeOpened

| Field | Description |
|---|---|
| **Producer** | fulfillment |
| **Consumers** | customer-service, admin-audit |
| **Trigger** | `OpenEvidenceDispute` command processed; offline evidence conflict detected. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `disputeId` | string | yes | Dispute identifier. Format: `disp-<uuid>`. |
| `fulfillmentRecordId` | string | yes | Fulfillment record identifier (`fr-<uuid>`). |
| `entitlementId` | `EntitlementId` | yes | Reference to the entitlement (`ent-<uuid>`). |
| `disputeType` | string | yes | Type of dispute: `OFFLINE_EVIDENCE_CONFLICT`, `DUPLICATE_BOARDING`, `UNRECOGNIZED_CHECK_IN`. |
| `evidenceSummary` | string | yes | Summary of the conflicting evidence. |
| `openedAt` | RFC3339 UTC | yes | When the dispute was opened. |
| `openedBy` | string | yes | Who opened the dispute: `SYSTEM`, `ADMIN`, `PROVIDER`. |

### EvidenceDisputeResolved

| Field | Description |
|---|---|
| **Producer** | fulfillment |
| **Consumers** | customer-service, admin-audit |
| **Trigger** | `ResolveEvidenceDispute` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `disputeId` | string | yes | Dispute identifier (`disp-<uuid>`). |
| `fulfillmentRecordId` | string | yes | Fulfillment record identifier (`fr-<uuid>`). |
| `resolution` | string | yes | Resolution: `UPHELD`, `REJECTED`, `INCONCLUSIVE`. |
| `reason` | string | yes | Reason for the resolution. |
| `resolvedAt` | RFC3339 UTC | yes | When the dispute was resolved. |
| `resolvedBy` | string | yes | Who resolved the dispute. |

## Accepted Commands

### RecordCheckIn

| Field | Description |
|---|---|
| **Sender** | station-device, provider-integration, admin-audit |
| **Trigger** | Passenger checks in at station or gate. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `fulfillmentRecordId` | string | yes | Fulfillment record identifier (`fr-<uuid>`). |
| `entitlementId` | `EntitlementId` | yes | Reference to the entitlement (`ent-<uuid>`). |
| `source` | string | yes | Source: `GATE`, `STATION`, `PROVIDER`, `CONDUCTOR`, `ADMIN`. |
| `sourceEventId` | string | yes | Source event identifier for idempotency. |
| `occurredAt` | RFC3339 UTC | yes | When the check-in physically occurred. |

### VerifyBoarding

| Field | Description |
|---|---|
| **Sender** | gate-device, provider-integration, conductor-app |
| **Trigger** | Passenger boarding verified at gate or by conductor. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `fulfillmentRecordId` | string | yes | Fulfillment record identifier (`fr-<uuid>`). |
| `entitlementId` | `EntitlementId` | yes | Reference to the entitlement (`ent-<uuid>`). |
| `source` | string | yes | Source: `GATE`, `STATION`, `PROVIDER`, `CONDUCTOR`, `ADMIN`. |
| `sourceEventId` | string | yes | Source event identifier for idempotency. |
| `occurredAt` | RFC3339 UTC | yes | When the boarding physically occurred. |

### RecordNoShow

| Field | Description |
|---|---|
| **Sender** | system, admin-audit |
| **Trigger** | Boarding window expired or manual no-show assessment. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `fulfillmentRecordId` | string | yes | Fulfillment record identifier (`fr-<uuid>`). |
| `reason` | string | yes | No-show reason: `BOARDING_WINDOW_EXPIRED`, `VERIFICATION_FAILED`, `MANUAL_RECORD`. |
| `assessedAt` | RFC3339 UTC | yes | When the no-show was assessed. |

### OpenEvidenceDispute

| Field | Description |
|---|---|
| **Sender** | system, admin-audit, customer-service |
| **Trigger** | Offline evidence conflict or duplicate boarding detected. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `fulfillmentRecordId` | string | yes | Fulfillment record identifier (`fr-<uuid>`). |
| `entitlementId` | `EntitlementId` | yes | Reference to the entitlement (`ent-<uuid>`). |
| `disputeType` | string | yes | Type of dispute. |
| `evidenceSummary` | string | yes | Summary of the conflicting evidence. |
| `openedBy` | string | yes | Who opened the dispute. |

### ResolveEvidenceDispute

| Field | Description |
|---|---|
| **Sender** | admin-audit, customer-service |
| **Trigger** | Dispute investigation completed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `disputeId` | string | yes | Dispute identifier (`disp-<uuid>`). |
| `resolution` | string | yes | `UPHELD`, `REJECTED`, `INCONCLUSIVE`. |
| `reason` | string | yes | Reason for the resolution. |
| `resolvedBy` | string | yes | Who resolved the dispute. |
