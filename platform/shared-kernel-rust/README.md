# shared-kernel

Domain: Shared Kernel / Platform

Language: rust

Phase: phase-1-foundation

Status: skeleton

## Owns

- reference implementation for IDs, value objects, and event envelope
- language-neutral contract source before code generation exists

## DDD Sources

- `docs/03-ddd-final/implementation-roadmap.md`
- `docs/03-ddd-final/phase-1-contract.md`

## Language Rationale

Rust keeps shared invariant examples strict while the normative contract remains language-neutral.

## Skeleton Check

```bash
cargo test
```
