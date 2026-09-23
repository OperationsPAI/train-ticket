# Invoicing

Rust service for invoice titles, deterministic SIM invoice requests, red flush observations and itinerary receipt projections.
Remaining work is tracked in [TODO](../../docs/TODO.md).

Invoice creation reads order and financial projections through the same PostgreSQL connection as its command transaction.
The command does not acquire another pool connection while holding account and idempotency locks.
`recorded_invoice_request_uses_one_connection_and_replays_once` uses an actual recorded title, request and projections in an isolated database schema with a single connection.
Set `TEST_DATABASE_URL` and `TEST_INVOICE_CONNECTION_SOURCE` and run `cargo test recorded_invoice_request_uses_one_connection_and_replays_once -- --ignored --nocapture`.
It verifies issuance, stable replay and unchanged event count on replay.

Amount basis reads require both order and finance projections, a recorded expected total, and identical currency and minor units.
Invoice creation applies the same check in its command transaction.
Missing context returns HTTP 404 for the amount basis and HTTP 412 for invoice creation without writing a command result.
`amount_basis_recorded` verifies missing order, absent total, partial financial totals, complete reads and idempotent invoice issuance using recorded production facts.

The recorded test uses an isolated PostgreSQL schema and requires `TEST_DATABASE_URL` with the `--ignored` Cargo option.
Its recorded run passes in 0.25 seconds.

The Journey Order stream uses four consumers in the same group with distinct consumer names.
Projection writes retain order scoped PostgreSQL locks and source event timestamps.
Creation events supply references without replacing a later confirmation or adjustment total.
`order_concurrency_recorded` verifies all six event orders, 144 concurrent duplicate deliveries, independent order progress and service reconstruction.
Its recorded PostgreSQL run passes in 0.41 seconds; live throughput remains separately measured.
Both changes are deployed in revision 276.
Loadgen's `TestLiveRecordedInvoiceProjectionCompletes` completes the recorded failed order through the current APIs in 0.088 seconds and retains the issued invoice.
Peak period acceptance is tracked in TODO.

## Recorded wallet compensation tests

`src/adapters/compensation_recorded_tests.rs` verifies PostgreSQL state through the actual subscription handler using recorded case data and events.
`tests/fixtures/wallet-compensation-recorded.json` contains unchanged revision 274 approval and applied envelopes for case `psc-01a0ceba-9bb5-7fbe-ac91-2168e09ae5ad` and its original order, amount and recognition projections.
The source amount basis is CNY 2886 minor units and has no reversal.
This order has no issued invoice, so the test verifies that no invoice, request or red flush is created and that all original projections remain unchanged.
The historical fixture additionally preserves existing blue and red invoices, the invoice request and completed red flush.
That historical applied envelope has no `caseType`; the test verifies the matching persisted source case and adds its recorded `COMPENSATION` type only to the in memory handler input.
The stored historical envelope is unchanged.
The revision 274 test requires the original `caseType` and performs no payload mapping.

Both tests check complete command rows, projection rows, correction audit, idempotency records, processed events and outbox after duplicate delivery and service reconstruction.
Replay also succeeds after closing the database pool, demonstrating that these ignored events need no database access.
Each test creates and removes its own schema with five second statement and two second lock timeouts.
Run from this module against a disposable PostgreSQL database.

```bash
TEST_DATABASE_URL='postgresql://postgres@127.0.0.1:15432/postgres' cargo test compensation_recorded_tests --lib -- --ignored --nocapture
```

Verified on 2026-09-23: two tests passed with no failures or skips in the explicit recorded test run.
They remain ignored in ordinary Cargo test runs because they require PostgreSQL.
The event recordings came from Redis streams, with invoice records and projections read by their recorded identifiers.
