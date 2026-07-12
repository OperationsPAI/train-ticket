# Notification — Events & Commands

Last updated: 2026-07-04

## Published Events

### NotificationScheduled

| Field | Description |
|---|---|
| **Producer** | notification |
| **Consumers** | none |
| **Trigger** | `ScheduleNotification` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `notificationTaskId` | string | yes | Notification task identifier. Format: `nt-<uuid>`. |
| `templateCode` | string | yes | Notification template identifier. |
| `recipientRef` | string | yes | Recipient reference. Format: `tvl-<uuid>` or `usr-<uuid>`. |
| `channel` | enum | yes | `EMAIL`, `SMS`, `PUSH`, `IN_APP`. |
| `intent` | string | yes | Notification intent (e.g. `ORDER_CONFIRMED`, `PAYMENT_RESULT`, `TICKET_ISSUED`). |
| `transactionRequired` | boolean | yes | Whether this notification is transaction-required and bypasses user preferences. |
| `scheduledAt` | RFC3339 UTC | yes | When scheduled. |

### NotificationDispatched

| Field | Description |
|---|---|
| **Producer** | notification |
| **Consumers** | none |
| **Trigger** | `DispatchNotification` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `notificationTaskId` | string | yes | Notification task identifier. |
| `channel` | enum | yes | `EMAIL`, `SMS`, `PUSH`, `IN_APP`. |
| `dispatchedAt` | RFC3339 UTC | yes | When the dispatch was initiated. |

### NotificationDelivered

| Field | Description |
|---|---|
| **Producer** | notification |
| **Consumers** | none |
| **Trigger** | `RecordDeliveryReceipt` command with `Delivered` outcome. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `notificationTaskId` | string | yes | Notification task identifier. |
| `channel` | enum | yes | `EMAIL`, `SMS`, `PUSH`, `IN_APP`. |
| `receiptId` | string | yes | Delivery receipt identifier. Format: `rct-<uuid>`. |
| `outcome` | enum | yes | `Delivered`. |
| `deliveredAt` | RFC3339 UTC | yes | When the delivery was confirmed. |

### NotificationFailed

| Field | Description |
|---|---|
| **Producer** | notification |
| **Consumers** | none |
| **Trigger** | `RecordDeliveryReceipt` command with non-Delivered outcome. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `notificationTaskId` | string | yes | Notification task identifier. |
| `channel` | enum | yes | `EMAIL`, `SMS`, `PUSH`, `IN_APP`. |
| `receiptId` | string | yes | Delivery receipt identifier. Format: `rct-<uuid>`. |
| `outcome` | enum | yes | `Bounced`, `Rejected`, `Timeout`, `Expired`. |
| `providerCode` | string | no | Provider error code. |
| `providerMessage` | string | no | Provider error message. |
| `failedAt` | RFC3339 UTC | yes | When the failure was recorded. |

### NotificationCancelled

| Field | Description |
|---|---|
| **Producer** | notification |
| **Consumers** | none |
| **Trigger** | `CancelNotification` command processed. RULING (2026-07-06): preference-based suppression is expressed as this event with `reason` = `SUPPRESSED_BY_PREFERENCES` (never emitted for `transactionRequired` notifications, which bypass preferences). There are no `NotificationSent`/`NotificationSuppressed` events — successful delivery is `NotificationDispatched` then `NotificationDelivered`. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `notificationTaskId` | string | yes | Notification task identifier. |
| `reason` | string | yes | Reason for cancellation. |
| `cancelledAt` | RFC3339 UTC | yes | When the cancellation was recorded. |

## Consumed Events

Notification subscribes to `events:journey-order`, `events:booking-orchestration`,
`events:payment`, `events:entitlement-ticketing`, and `events:post-sales`
(messaging.md subscription table) and schedules notifications from those facts.

## Key Commands

### ScheduleNotification

| Field | Type | Required | Description |
|---|---|---|---|
| `notificationTaskId` | `NotificationTaskId` | yes | Unique task identifier (`nt-<uuid>`). |
| `triggerEventId` | `EventId` | yes | Source event that triggered this notification (`evt-<uuid>`). |
| `triggerEventType` | string | yes | Type of the trigger event (e.g. `JourneyOrderCreated`). |
| `recipientRef` | `RecipientRef` | yes | Recipient reference (`tvl-<uuid>` or `usr-<uuid>`). |
| `templateCode` | string | yes | Notification template code. |
| `channel` | enum | yes | `EMAIL`, `SMS`, `PUSH`, `IN_APP`. |
| `intent` | string | yes | Notification intent. |
| `transactionRequired` | boolean | yes | Whether this is a transaction-required notification. |
| `variables` | map(string,string) | yes | Template variable substitutions. |
| `scheduledAt` | RFC3339 UTC | yes | When the task is scheduled. |

### DispatchNotification

| Field | Type | Required | Description |
|---|---|---|---|
| `notificationTaskId` | string | yes | Task to dispatch. |
| `dispatchedAt` | RFC3339 UTC | yes | When dispatch occurs. |

### RecordDeliveryReceipt

| Field | Type | Required | Description |
|---|---|---|---|
| `receiptId` | string | yes | Unique receipt identifier (`rct-<uuid>`). |
| `notificationTaskId` | string | yes | Task this receipt applies to. |
| `channel` | enum | yes | `EMAIL`, `SMS`, `PUSH`, `IN_APP`. |
| `outcome` | enum | yes | `Delivered`, `Bounced`, `Rejected`, `Timeout`, `Expired`. |
| `providerCode` | string | no | Provider error code. |
| `providerMessage` | string | no | Provider error message. |
| `recordedAt` | RFC3339 UTC | yes | When the receipt is recorded. |

### CancelNotification

| Field | Type | Required | Description |
|---|---|---|---|
| `notificationTaskId` | string | yes | Task to cancel. |
| `reason` | string | yes | Reason for cancellation. |
| `cancelledAt` | RFC3339 UTC | yes | When cancellation occurs. |

## Idempotency Key Convention

Idempotency for send operations uses the key format:

```
idem-<triggerEventId>:<recipientRef>:<templateCode>
```

Same trigger event + recipient + template yields at most one task.
Retries reference the same task.
