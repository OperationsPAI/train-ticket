# Notification — HTTP API

Last updated: 2026-07-05

## Overview

Notification manages notification tasks: scheduling, dispatching, and
recording delivery receipts. All notification commands are **bus-only** —
there are no external HTTP endpoints for sending notifications.
Notifications are triggered by events published by other contexts.

## Bus-only commands

The following commands are consumed from the event bus only and have no HTTP
endpoint:

| Command | Trigger | Description |
|---|---|---|
| `ScheduleNotification` | Domain events (e.g. `JourneyOrderConfirmed`, `EntitlementIssued`) | Schedule a notification for a recipient. |
| `DispatchNotification` | Internal scheduler | Dispatch a scheduled notification via the appropriate channel. |
| `RecordDeliveryReceipt` | Channel callback | Record delivery success or failure. |

## Health endpoints only

- `GET /healthz` — liveness
- `GET /readyz` — readiness

No business HTTP endpoints are exposed.

## Open Issues

- None.
