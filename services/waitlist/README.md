# waitlist

Domain: Waitlist

Language: rust

Phase: future-scope

Status: placeholder-skeleton

## Owns

- WaitlistRequest
- QueuePolicy
- FulfillmentWindow

## DDD Sources

- `docs/02-domains/waitlist.md`

## Language Rationale

Rust fits fairness, mutual-exclusion, and queue-order invariants.

## Skeleton Check

```bash
cargo test
```
