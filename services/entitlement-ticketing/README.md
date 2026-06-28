# entitlement-ticketing

Domain: Entitlement & Ticketing

Language: rust

Phase: phase-1-core

Status: skeleton

## Owns

- Entitlement
- Credential
- Issue
- Void
- Suspend
- Boarded consumption

## DDD Sources

- `docs/02-domains/entitlement-ticketing.md`

## Language Rationale

Rust is appropriate for ticket entitlement lifecycle invariants and credential safety.

## Skeleton Check

```bash
cargo test
```
