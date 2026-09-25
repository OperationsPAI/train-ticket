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
