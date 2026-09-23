# Invoicing

Rust implementation of the Wave-A Invoicing bounded context. It owns invoice titles, deterministic SIM blue e-invoice requests, red-flush observations, and read-only itinerary receipt projections.

Invoice creation reads order and financial projections through the same PostgreSQL connection as its command transaction.
The command does not acquire another pool connection while holding account and idempotency locks.
`recorded_invoice_request_uses_one_connection_and_replays_once` uses an actual recorded title, request and projections in an isolated database schema with a single connection.
Set `TEST_DATABASE_URL` and `TEST_INVOICE_CONNECTION_SOURCE` and run `cargo test recorded_invoice_request_uses_one_connection_and_replays_once -- --ignored --nocapture`.
It verifies issuance, stable replay and unchanged event count on replay.
