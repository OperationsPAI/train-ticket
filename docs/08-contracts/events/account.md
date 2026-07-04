# Account Domain Events

Last updated: 2026-07-05

## Producer: `account`

## Consumed commands

| Command | Sender | Payload | Description |
|---|---|---|---|
| `CreateAccount` | Self / Traveler Profile | `{ accountId?: AccountId, correlationId?: CorrelationId }` | Create a new user account. |
| `OpenSession` | Self / Auth Gateway | `{ accountId: AccountId, sessionId?: SessionId, correlationId?: CorrelationId }` | Open a new session. |
| `RevokeSession` | Self / Auth Gateway | `{ sessionId: SessionId, accountId: AccountId, reason: SessionRevokeReason, correlationId?: CorrelationId }` | Revoke an active session. |
| `FreezeAccount` | Risk & Compliance / Customer Service | `{ accountId: AccountId, reason: string, operator: string, caseRef?: string, correlationId?: CorrelationId }` | Freeze an account. |
| `UnfreezeAccount` | Customer Service | `{ accountId: AccountId, reason: string, correlationId?: CorrelationId }` | Unfreeze an account. |
| `UpdatePreference` | Self / Client | `{ accountId: AccountId, preferenceKey: string, value: string, correlationId?: CorrelationId }` | Update an account preference. |
| `StartAccountClosure` | Self / Customer Service | `{ accountId: AccountId, closureRequestId?: ClosureRequestId, correlationId?: CorrelationId }` | Start account closure saga. |
| `CompleteAccountClosure` | Self (Saga) | `{ accountId: AccountId, closureRequestId: ClosureRequestId, correlationId?: CorrelationId }` | Finalise irreversible closure. |

## Published events

### AccountCreated

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | `AccountId` | yes | The created account ID (`acct_<uuid>`). |
| `occurredAt` | RFC3339 UTC | yes | When the account was created. |
| `correlationId` | `CorrelationId` | no | Business transaction correlation. |

**Consumers:** Traveler Profile, Journey Order, Notification, Reporting.

### AccountFrozen

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | `AccountId` | yes | Frozen account ID. |
| `reason` | string | yes | Reason for freezing. |
| `operator` | string | yes | Who initiated the freeze (system, admin, customer-service). |
| `caseRef` | string | no | Customer service case reference. |
| `occurredAt` | RFC3339 UTC | yes | When the freeze was applied. |
| `correlationId` | `CorrelationId` | no | Business transaction correlation. |

**Consumers:** Journey Order, Payment, Notification, Risk & Compliance, Customer Service.

### AccountUnfrozen

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | `AccountId` | yes | Unfrozen account ID. |
| `reason` | string | yes | Reason for unfreezing. |
| `occurredAt` | RFC3339 UTC | yes | When the unfreeze was applied. |
| `correlationId` | `CorrelationId` | no | Business transaction correlation. |

**Consumers:** Journey Order, Payment, Notification, Risk & Compliance, Customer Service.

### AccountClosureStarted

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | `AccountId` | yes | Account being closed. |
| `closureRequestId` | `ClosureRequestId` | yes | Saga request ID (`clr_<uuid>`). |
| `occurredAt` | RFC3339 UTC | yes | When closure was requested. |
| `correlationId` | `CorrelationId` | no | Business transaction correlation. |

**Consumers:** Journey Order, Payment, Customer Service.

### AccountClosed

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | `AccountId` | yes | Closed account ID. |
| `closureRequestId` | `ClosureRequestId` | yes | Saga request ID. |
| `final` | boolean | yes | Always `true`. Closure is irreversible. |
| `occurredAt` | RFC3339 UTC | yes | When closure was finalised. |
| `correlationId` | `CorrelationId` | no | Business transaction correlation. |

**Consumers:** Traveler Profile, Journey Order, Payment, Notification, Risk & Compliance, Customer Service, Reporting.

### SessionOpened

| Field | Type | Required | Description |
|---|---|---|---|
| `sessionId` | `SessionId` | yes | Opened session ID (`sess_<uuid>`). |
| `accountId` | `AccountId` | yes | Account that owns the session. |
| `occurredAt` | RFC3339 UTC | yes | When the session was opened. |
| `correlationId` | `CorrelationId` | no | Business transaction correlation. |

**Consumers:** Risk & Compliance, Auth Gateway.

### SessionRevoked

| Field | Type | Required | Description |
|---|---|---|---|
| `sessionId` | `SessionId` | yes | Revoked session ID. |
| `accountId` | `AccountId` | yes | Account that owned the session. |
| `reason` | enum | yes | `LOGOUT`, `ACCOUNT_FROZEN`, `ACCOUNT_CLOSED`, `FORCED`. |
| `occurredAt` | RFC3339 UTC | yes | When the session was revoked. |
| `correlationId` | `CorrelationId` | no | Business transaction correlation. |

**Consumers:** Risk & Compliance, Auth Gateway.

### PreferenceUpdated

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | `AccountId` | yes | Account whose preference changed. |
| `preferenceKey` | string | yes | Preference key (e.g. `notification.email`, `lang`). |
| `oldValue` | string | no | Previous value, if any. |
| `newValue` | string | yes | New value. |
| `occurredAt` | RFC3339 UTC | yes | When the preference was updated. |
| `correlationId` | `CorrelationId` | no | Business transaction correlation. |

**Consumers:** Notification, Client, Reporting.

## SessionRevokeReason enum

| Value | Description |
|---|---|
| `LOGOUT` | User initiated logout. |
| `ACCOUNT_FROZEN` | Session revoked due to account freeze. |
| `ACCOUNT_CLOSED` | Session revoked due to account closure. |
| `FORCED` | Session revoked by admin or risk system. |

## EventEnvelope usage

All events are published inside the standard `EventEnvelope`:

```json
{
  "eventId": "evt-<uuid>",
  "eventType": "AccountCreated",
  "occurredAt": "2026-07-03T10:00:00Z",
  "correlationId": "corr-<uuid>",
  "causationId": "cmd-<uuid>",
  "producer": "account",
  "schemaVersion": 1,
  "payload": { ... event-specific fields ... }
}
```
