# Payment Channel

Go implementation of the ADR-0003 Wave A simulated payment-channel boundary.

The service owns deterministic SIM channel orders, original-route refunds, daily
channel statements, and reconciliation discrepancies for `ALIPAY_SIM`,
`WECHAT_SIM`, and `UNIONPAY_SIM`. It persists aggregate snapshots with the shared
Go kit tables (`outbox`, `idempotency_records`, `processed_events`) and publishes
contract envelopes to `events:payment-channel` through the outbox relay.

Daily statement membership uses the immutable successful payment/refund
`completedAt` UTC date. Later channel status queries may update an aggregate
snapshot, but cannot move the settled fact into a different statement day.

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `payment_channel` on the `txn` PostgreSQL instance
  (`services.payment-channel.db`, `services.payment-channel.pgInstance` and
  `postgres.instances.txn.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/ready`,
`/metadata`.

## HTTP surface

- `/api/v1/channel-orders` create, read, `{id}/submit`, `{id}/query`.
- `/api/v1/channel-refunds` create, read, `{id}/submit`, `{id}/query`.
- `/api/v1/channel-statements` list, `generate`, `{id}`, `{id}/freeze`,
  `{id}/lines` (ordinal cursor, page size at most 500).
- `/api/v1/channel-discrepancies` list, create, `{id}`, `{id}/resolve`.

The full contract lives in
[`docs/08-contracts/api/payment-channel.md`](../../docs/08-contracts/api/payment-channel.md).

## Events

Publishes `ChannelOrderCreated`, `ChannelOrderSubmitted`,
`ChannelOrderSucceeded`, `ChannelOrderFailed`, `ChannelOrderMissed`,
`ChannelRefundSucceeded`, `ChannelRefundFailed`, `ChannelRefundMissed`,
`ChannelStatementGenerated`, `ChannelStatementFrozen` and the discrepancy facts
to `events:payment-channel`; see
[`docs/08-contracts/events/payment-channel.md`](../../docs/08-contracts/events/payment-channel.md).
It consumes no inbound event stream.

## Metrics

Emits `train_ticket.payment.operations` and `train_ticket.payment.amount` for the
edge it settles (`internal/metrics/metrics.go`); the contract is in
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Build and test

```bash
cd services/payment-channel && go test ./...
```

Live PostgreSQL tests skip when `TEST_DATABASE_URL` and their recorded fixture
variables are unset.

## Statement line storage

Migration `005_normalized_statement_lines.sql` stores new statement metadata in
`channel_statement_snapshots` and immutable line facts in
`channel_statement_lines`. Statement generation, summary reads and freezing no
longer read or rewrite a full day's line array. Lines are read through
`GET /api/v1/channel-statements/{channelStatementId}/lines` with the `after`
ordinal cursor and a maximum page size of 500.

The schema migration deliberately leaves existing JSON `lines` in place so the
migration transaction stays short. After the new binary and migration are
available, normalize retained statements with the same database configuration
used by the service:

```bash
DATABASE_URL='postgres://...' \
MIGRATIONS_DIR=/app/migrations \
STATEMENT_BACKFILL_BATCH_SIZE=10 \
payment-channel backfill-statement-lines
```

The command commits one statement at a time. It copies the original JSON lines
without changing statement or line IDs, verifies the ordered JSON digest and
line count, then atomically removes the old array. It is safe to stop and run
again. A malformed statement is left unchanged and causes a non-zero exit so it
can be investigated instead of silently losing reconciliation evidence.
