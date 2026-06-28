# capacity-availability

Domain: Capacity & Availability

Language: rust

Phase: phase-1-core

Status: skeleton

## Owns

- InventoryPool
- CapacityHold
- Quota

## DDD Sources

- `docs/02-domains/capacity-availability.md`

## Language Rationale

Rust is appropriate for interval overlap and seat-hold invariants that must fail closed.

## Skeleton Check

```bash
cargo test
```
