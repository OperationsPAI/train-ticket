# Post Sales — Events & Commands

Last updated: 2026-06-28

## Published Events

### PostSalesRequested

| Field | Description |
|---|---|
| **Producer** | post-sales |
| **Consumers** | fare-pricing |
| **Trigger** | `RequestPostSales` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Canonical case ID (`psc-<uuid>`). |
| `orderId` | `JourneyOrderId` | yes | Parent order ID. |
| `requestType` | enum | yes | `CANCELLATION`, `REFUND_BY_RULE`, `CHANGE`. |
| `requestedAt` | RFC3339 UTC | yes | Request timestamp. |

### PostSalesApproved

| Field | Description |
|---|---|
| **Producer** | post-sales |
| **Consumers** | entitlement-ticketing, capacity-availability, payment |
| **Trigger** | Post-sales case approved for execution. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `orderId` | `JourneyOrderId` | yes | Parent order. |
| `approvedActions` | object | yes | Actions to execute. |

### PostSalesApplied

| Field | Description |
|---|---|
| **Producer** | post-sales |
| **Consumers** | journey-order, notification |
| **Trigger** | All post-sales execution steps completed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `orderId` | `JourneyOrderId` | yes | Parent order. |
| `resultSummary` | object | yes | Summary of executed actions. |
