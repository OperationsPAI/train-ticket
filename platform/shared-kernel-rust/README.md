# shared-kernel

Domain: Shared Kernel / Platform

Language: rust

Phase: phase-1-foundation

Status: contract-baseline

## Owns

- reference implementation for IDs, value objects, and event envelope
- language-neutral contract source before code generation exists

## Contract Baseline

The current baseline covers the smallest shared vocabulary needed before
bounded contexts exchange events or references:

- `PlaceRef`, `TravelerRef`, and `SegmentRef` stable cross-context references.
- `Money` with minor-unit amount and three-letter uppercase currency code.
- `UnixMillis` and `TimeWindow` for schedule and event timestamps.
- `EventEnvelope` with `event_id`, `event_type`, `schema_version`,
  `occurred_at`, `correlation_id`, and optional `causation_id`.

The Rust implementation is a reference for invariants only. Serialization
shape and generated language bindings remain future work.

## DDD Sources

- `docs/03-ddd-final/implementation-roadmap.md`
- `docs/03-ddd-final/phase-1-contract.md`

## Language Rationale

Rust keeps shared invariant examples strict while the normative contract remains language-neutral.

## Skeleton Check

```bash
cargo test
```
