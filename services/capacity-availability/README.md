# Capacity and Availability

Rust service for PostgreSQL inventory pools, Hold admission and lifecycle, availability reads and Redis event integration.

## Supply integration

The supply integration reads accepted reservation references from Booking, segment schedule facts from Service Plan, and published unit or quota facts from Seat Assignment.
Availability and admission combine active allocations with pending holds and deduplicate them by Hold identifier.
The standing policy is documented in [Standing capacity policy](../../docs/08-contracts/standing-capacity-policy.md).
See [integration requirements](../../docs/10-domain-enrichment/capacity-overbooking.md) for the remaining rollout gates.
This code path is still being integrated and must not be described as deployed solely because the source tests pass.

`SUPPLY_FACTS_BASE_URL_TEMPLATE` selects upstream service addresses and defaults to `http://%s:8080`.
`DATABASE_URL`, `REDIS_URL` and `MIGRATIONS_DIR` configure persistence and messaging.
`reservation_supply_facts` stores accepted source references and `capacity_supply_observations` stores supply evidence.
Historical Hold identities are preserved.
Multiple HTTP keys and event delivery for the same booking share one Hold identity; released bookings do not obtain another Hold through replay.
Initial purchase history uses Journey Order's `purchase-source` endpoint; replacement history uses its change case's target Offer.
Historical source resolution uses at most eight concurrent upstream reservation lookups per service instance and commits verified facts before inventory admission.
Subsequent queries reuse the persisted facts, including after a process restart or a refused Hold.

## Validation

Run from the repository root:

```bash
cargo test --manifest-path services/capacity-availability/Cargo.toml --features redis-impl
```

External database tests are explicitly ignored by default.
`tests/supply_hold_live.rs` requires `TEST_DATABASE_URL`, `SUPPLY_FACTS_BASE_URL_TEMPLATE`, `TEST_SUPPLY_BOOKING`, `TEST_AVAILABLE_SUPPLY_BOOKING`, `TEST_HISTORICAL_SUPPLY_HOLD` and `TEST_EXISTING_SUPPLY_INVENTORY` when running all its tests.
It reads actual upstream service facts and creates isolated local database schemas.
The complete inventory test checks cold and repeated query deadlines, durable source facts, service reconstruction, and unchanged historical inventory.
`tests/standing_supply_live.rs` uses `TEST_STANDING_SUPPLY`, `TEST_STANDING_BOOKING`, `TEST_DATABASE_URL` and `SUPPLY_FACTS_BASE_URL_TEMPLATE` to verify standing admission without physical seat creation.
`tests/recorded_supply.rs` validates recorded composition and allocation data.
Provide `TEST_SEAT_SUPPLY`, `TEST_SUPPLY_SEGMENT`, `TEST_SUPPLY_BOOKING` and `TEST_SUPPLY_BASE_URL_TEMPLATE` for its explicit run.

```bash
cargo test --manifest-path services/capacity-availability/Cargo.toml --features redis-impl --test supply_hold_live -- --ignored
cargo test --manifest-path services/capacity-availability/Cargo.toml --features redis-impl --test recorded_supply -- --ignored
cargo test --manifest-path services/capacity-availability/Cargo.toml --features redis-impl --test standing_supply_live -- --ignored
```

Use a disposable PostgreSQL database for these tests.
They do not create or modify production allocations.

## Boundary

Capacity does not issue tickets, assign passenger seat numbers, capture payment or edit commercial orders.
The [HTTP API](../../docs/08-contracts/api/capacity-availability.md) documents its external fields.
The shared runtime exposes health, liveness, readiness and metadata endpoints.
