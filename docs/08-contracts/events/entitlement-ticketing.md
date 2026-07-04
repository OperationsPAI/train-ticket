# Entitlement & Ticketing — Events & Commands

Last updated: 2026-06-28

## Published Events

### EntitlementIssued

| Field | Description |
|---|---|
| **Producer** | entitlement-ticketing |
| **Consumers** | booking-orchestration, notification |
| **Trigger** | `IssueEntitlement` command processed; ticket/credential generated. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Canonical entitlement ID (`ent-<uuid>`). |
| `segmentBookingId` | `SegmentBookingId` | yes | Reference to the segment booking. |
| `travelerRef` | `TravelerId` | yes | Traveler reference. |
| `credentialNo` | string | yes | Unique credential/ticket number. |
| `issuedAt` | RFC3339 UTC | yes | Issue timestamp. |

### EntitlementVoided

| Field | Description |
|---|---|
| **Producer** | entitlement-ticketing |
| **Consumers** | booking-orchestration, notification, post-sales |
| **Trigger** | `VoidEntitlement` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Entitlement ID. |
| `segmentBookingId` | `SegmentBookingId` | yes | Reference to the segment booking. |
| `voidedAt` | RFC3339 UTC | yes | Void timestamp. |
| `reason` | string | yes | Void reason. |

### EntitlementBoarded

| Field | Description |
|---|---|
| **Producer** | entitlement-ticketing |
| **Consumers** | fulfillment |
| **Trigger** | Passenger boarded the service. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Entitlement ID. |
| `boardedAt` | RFC3339 UTC | yes | Boarding timestamp. |

### EntitlementSuspended

| Field | Description |
|---|---|
| **Producer** | entitlement-ticketing |
| **Consumers** | post-sales, notification |
| **Trigger** | Entitlement suspended due to dispute or risk. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Entitlement ID. |
| `suspendedAt` | RFC3339 UTC | yes | Suspension timestamp. |
| `reason` | string | yes | Suspension reason. |
