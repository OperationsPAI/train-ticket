# trip-planning

Domain: Trip Planning

Language: rust

Phase: phase-1-search-foundation

Status: REQ-008 search foundation, REQ-032 HTTP API and event bus adapter

## Owns

- `TripIntent` validation: origin/destination resolution, date windows, passenger mix, and the rejection reasons that make an empty result explainable rather than silent.
- Itinerary candidate assembly from the plan index, including multi-leg connections.
- `SearchResult` ranking with per-factor explanations, so a ranking can be audited rather than trusted.
- Non-authoritative `PriceHint` and `AvailabilityHint` snapshots. These are hints: Trip Planning never holds inventory and never quotes a binding fare.

## Boundary

Read-only over other contexts. The plan index (`src/plan_index.rs`) is built by
consuming `ServicePlanPublished`, `ServicePlanChanged`, `ScheduledServiceCreated`,
`ServiceSegmentCreated`, `TransportNodeUpdated` and `PlaceNetworkUpdated`; it
stores references to Service Plan and Place & Network concepts without importing
their internals. Nothing here mutates capacity, fare, offer, or order state.

## Why Rust, and what that cost

This service was originally Python. Search is the most CPU-bound path in the
system — candidate assembly and ranking run per request over the whole plan index
— so it was rewritten in Rust for throughput, and the Python implementation has
been deleted. The image is still named `train-ticket/trip-planning`; note that
`deploy/docker/trip-planning/Dockerfile` builds *this* crate despite the
directory name lacking the `-rs` suffix.

One capability did not carry over: **minimum connection times are not
enforced.** The Python version consumed `MctRulePublished` / `MctRuleRetired`
from transfer-management and kept a rule table keyed by `(mctRuleId, version)`.
`PlanIndex::apply_event` has no arm for those events, so while multi-leg results
are produced and penalised by `connection_count()`, no connection is checked
against the operator's declared minimum — this service can return an itinerary
whose connection is too short to actually make. transfer-management still
publishes those events and offer-management still consumes them, so this is a
gap on this side, not a retired contract. Tracked in
`deploy/KNOWN-ISSUES.md`.

## DDD Sources

- `docs/02-domains/trip-planning.md`
- `docs/03-ddd-final/phase-1-contract.md`

## Validation

```bash
cargo test --manifest-path services/trip-planning-rs/Cargo.toml
```
