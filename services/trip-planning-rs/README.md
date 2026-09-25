# Trip Planning

Rust service for itinerary search using Axum, PostgreSQL and Redis Streams.
The image is `train-ticket/trip-planning`, built by `deploy/docker/trip-planning/Dockerfile`.
The domain document is [Trip Planning](../../docs/02-domains/trip-planning.md).
Remaining work is tracked in [docs/TODO.md](../../docs/TODO.md).

## Search behavior

`POST /api/v1/itineraries/search` resolves origin and destination references, finds planned segments for the requested departure date, ranks candidates and stores itinerary snapshots.
`GET /api/v1/itineraries/{itineraryRef}` reads a saved snapshot.
Search remains direct-only unless the request explicitly sets `maxConnections` above zero.
When enabled, the index assembles connected train itineraries up to three connections and only accepts each connection when an applicable published minimum connection time rule is present.
The request's `minConnectionMinutes` can make that minimum stricter but cannot weaken the published rule.

`src/plan_index.rs` stores segments by reference and indexes their references by departure date and origin node.
Node mappings support both node to place and place to nodes lookup.
Queries resolve the current mappings, including updates received after segment creation.
Moving a segment to another date or origin removes its old index membership.
An empty lookup produces an empty itinerary list.

## Events and storage

The index consumes `ServicePlanChanged`, `ServiceSegmentCreated`, `TransportNodeRegistered`, `TransportNodeAdded`, `TransportNodeUpdated`, `MctRulePublished` and `MctRuleRetired`.
Segment, node and rule changes persist to `plan_segments`, `plan_nodes` and `plan_mct_rules`, which rebuild the index at startup.
Retired rules remain as version tombstones so an out-of-order publication cannot revive them.
The active filter does not index service publication alone, delay, cancellation, restoration or temporary service events.

Search results persist to `itinerary_snapshots`, and `ItineraryProposed` is appended through the outbox.
Snapshot persistence and outbox append are separate database operations.
The current publish method logs append failures, so a successful search response alone does not prove event persistence.

## Current boundaries

Trip Planning does not hold capacity, create a commercial Offer or modify orders.
Generated price hints contain zero CNY and availability hints use `UNKNOWN`; they are not current Fare quotes or Capacity availability.
Only unconditional `STATION` to `STATION` rules apply because the current Service Plan feed identifies train stops but does not publish the dimensions needed to evaluate conditional rules.
The same stop, or two nodes mapped to the same place, is classified as `SAME_STATION`; other transfer categories fail closed until authoritative topology is available.
The HTTP response does not expose all internal score explanations or exclusion reasons.

The current Service Plan creation API defines two stops and an explicit supply interval of `[0,1)`.
Published seasonal metadata and temporary service records do not automatically add searchable supply.

## Verification

[Search performance evidence](docs/search-performance-20260923.md) records deployment of the date and origin index in Helm revision 277.
It includes recorded candidate and response comparisons and local Axum plus PostgreSQL concurrency measurements.
Those measurements do not establish a complete production peak cycle or all cross service behavior.
No tests were rerun for this documentation update.

Existing tests can be run from the repository root:

```bash
cargo test --manifest-path services/trip-planning-rs/Cargo.toml
```

Recorded PostgreSQL tests require the inputs and explicit commands documented in the performance evidence.
