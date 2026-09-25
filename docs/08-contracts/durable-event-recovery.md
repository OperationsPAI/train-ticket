# Durable event recovery

Last updated: 2026-09-25.

This document defines the implemented T05 recovery boundary. Redis Streams is
the live transport, not the historical source of record. Recovery therefore
uses PostgreSQL archives, consumer checkpoints and durable dead-letter
evidence rather than relying on Redis stream retention or short-lived outbox
and `processed_events` rows.

## Current scope

The durable replay source is enabled for the facts required by Notification,
Loyalty Membership and terminal compensation verification:

| Producer | Archived event types |
|---|---|
| Post Sales | `PostSalesApproved`, `PostSalesApplied`, `PostSalesFailed` |
| Payment | `PaymentCaptured`, `RefundRequested`, `RefundSettled`, `RefundFailed`, `PaymentRefunded` |
| Wallet / Promotion | `BenefitIssued`, `BenefitRevoked`, `BenefitExpired`, `BenefitRedemptionReversed` |

Other event types continue to use the normal transactional outbox and Redis
delivery path. This document does not claim historical replay for every event
stream in the system.

## Producer archive and retention

An archived event is inserted into `event_replay_archive` in the same database
transaction as the normal outbox row and domain state change. The archive
retains the original serialized envelope, source stream, event type, a
monotonic `archive_seq`, creation time and `retained_until`.

`EVENT_REPLAY_RETENTION_DAYS` controls retention. The default is 7 days; valid
values are 1 through 90 days. Expired rows are deleted in bounded batches and
the producer records a durable deletion watermark. This watermark is distinct
from the PostgreSQL sequence value, because rolled-back transactions may leave
sequence holes without losing an event.

Each producer exposes the internal read-only endpoint:

```text
GET /api/v1/internal/event-replay?after=<archive-sequence>&limit=<1..500>
```

The response includes the producer, requested cursor, oldest and newest
available sequence, retention start, `hasMore`, and ordered archived events.
If retention has deleted events newer than the supplied cursor, the producer
returns HTTP 410 with code `REPLAY_RETENTION_GAP`. A consumer must stop automatic
recovery at that point; skipping to the oldest available event would silently
lose business facts.

## Consumer replay checkpoints

Notification replays Post Sales, Payment and Wallet / Promotion archives.
Loyalty Membership replays Payment and Post Sales archives. Each consumer
stores one row per producer in `event_replay_checkpoints`.

Only one replica may process a consumer/source pair at a time. The worker takes
a PostgreSQL advisory lock, reads after the persisted sequence, sends every
event through the same handler used by Redis, and advances the checkpoint only
after handling completes. The consumer's normal `processed_events` transaction
remains the side-effect idempotency boundary.

If a process stops after committing a business effect but before advancing the
replay cursor, the next process fetches the event again. `processed_events`
suppresses the duplicate effect, after which the cursor advances. Source
failures set checkpoint status `ERROR`; retention loss sets `GAP`. Neither
condition advances the cursor.

The default poll interval is 5 seconds. `EVENT_REPLAY_POLL_INTERVAL_MS` may
override it with a value of at least 100 milliseconds.

## Durable dead-letter evidence

Notification and Loyalty Membership persist a dead letter in
`event_dead_letters` before writing the Redis DLQ entry and before acknowledging
the source message. If the database write fails, the Redis message remains
pending.

The evidence preserves:

- consumer group, consumer name, source stream and Redis message ID;
- event ID, event type, producer and the original serialized envelope;
- first and latest failure kind/reason, attempt count and timestamps;
- recovery source/sequence, status and any recovery error.

An identical later observation updates the latest failure and maximum attempts,
but cannot replace the original envelope or first failure evidence. Conflicting
reuse of the same event identity is rejected. Recovery status is `OPEN`,
`REPLAYING`, `RECOVERED` or `DISMISSED`.

## Terminal compensation mismatches

Post Sales stores a first-write-wins row in
`compensation_mismatch_checkpoints` when authoritative 2xx responses prove that
a wallet compensation case contradicts its approved decision or preserved
travel/payment rights. Examples include a settled refund on the same case, a
voided original ticket, a released original capacity item, a missing or
different Wallet benefit, or an `APPLIED` historical case without required
local issuance/verification evidence.

The checkpoint retains the frozen approved amount, case status, original
refund, Wallet, rights and local operation evidence, correlation ID and
detection time. Repeated and concurrent writes return the first checkpoint and
cannot overwrite it.

`POST /api/v1/post-sales-cases/{caseId}/execute-compensation` returns HTTP 409
`COMPENSATION_MISMATCH` with `automaticReplayAllowed=false` once such a
checkpoint exists. Every later call checks the checkpoint before making any
downstream request. The full record is available to internal operators through:

```text
GET /api/v1/internal/compensation-mismatches/{caseId}
```

Dependency timeouts and non-2xx responses are not converted into terminal
mismatches; they remain retryable failures. Before issuing a credit, Post Sales
uses Wallet's read-only case lookup:

```text
GET /api/v1/internal/benefits/by-case/{caseId}
```

If Wallet already committed the matching credit while Post Sales failed before
recording `compensationOperations.issued`, Post Sales adopts that benefit and
does not call `POST /api/v1/benefits` again. A mismatching benefit is frozen for
manual reconciliation.

## Verification

The automated evidence includes:

- deletion of the Redis source stream followed by recovery from an HTTP replay
  archive;
- a crash after the business transaction but before checkpoint advancement,
  followed by process reconstruction and one total business effect;
- durable DLQ evidence surviving store reconstruction, with duplicate
  observations unable to replace the original event or first failure;
- failure to persist DLQ evidence leaving the Redis message unacknowledged;
- discovery of an already-issued Wallet credit with zero duplicate POSTs;
- terminal refund/compensation mismatch persistence, process reconstruction,
  zero subsequent downstream calls and concurrent first-write-wins behavior.

These tests use isolated local PostgreSQL/Redis and HTTP stubs. No Kubernetes,
Helm, image push or deployment is part of this verification.
