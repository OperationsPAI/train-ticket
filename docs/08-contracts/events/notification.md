# Notification — Events & Commands

Last updated: 2026-06-28

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
| `notificationTaskId` | string | yes | Notification task identifier. |
| `templateCode` | string | yes | Notification template identifier. |
| `recipientRef` | string | yes | Recipient reference. |
| `channel` | enum | yes | `EMAIL`, `SMS`, `PUSH`, `IN_APP`. |
| `scheduledAt` | RFC3339 UTC | yes | When scheduled. |
