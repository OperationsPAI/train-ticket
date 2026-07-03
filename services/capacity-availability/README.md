# capacity-availability

Domain: Capacity & Availability

Language: rust

Phase: phase-1-core

Status: REQ-006 domain foundation

## Owns

- `InventoryPool` for fixed train service inventory keyed by scheduled service, service date, seat class/cabin or sellable unit, and route/service segment references.
- `StationInterval` half-open train section ranges `[fromStopSeq, toStopSeq)` with overlap detection.
- `CapacityHold` lifecycle: request, held, confirm, release, expire, failure, idempotency, and cross-context reference metadata.
- `AvailabilitySnapshot` read model evidence for Trip Planning and Offer Management that never locks inventory.

## Boundary

This slice is domain-only. It stores references to Service Plan and Place & Network concepts but does not import their internals. It publishes capacity facts such as `CapacityHeld`, `CapacityHoldConfirmed`, `CapacityReleased`, and `CapacityHoldExpired`; it does not mutate order, payment, fare, entitlement, provider booking, or JourneyOrder state.

## DDD Sources

- `docs/02-domains/capacity-availability.md`
- `docs/03-ddd-final/phase-1-contract.md`

## Validation

```bash
cargo test
```
