# ADR-0002: Future-Scope Domain Activation Order

Date: 2026-07-08
Status: accepted (orchestrator ruling, flagged for maintainer review)

## Context

Phase 1/2 delivered and hardened the 23-service core. Six future-scope
domains remain as skeletons: waitlist, wallet-promotion, dispatch,
disruption-recovery, transfer-management, ancillary-service. The maintainer
delegated activation sequencing ("全部推进").

## Decision

Activate in this order, one integration-heavy domain per wave, pairing a
small domain alongside where capacity allows:

1. **waitlist** (wave 15) — the live system already produces its trigger
   condition continuously (`NO_AVAILABLE_CAPACITY` sold-out cascades under
   resident load). Activation converts an existing failure mode into a
   business capability, and exercises the richest integration surface:
   capacity release feed, booking-orchestration hold authorization, payment
   pre-authorization references.
2. **wallet-promotion + dispatch** (wave 16) — both small (≈80-line domain
   docs), independent of each other; dispatch also unblocks
   disruption-recovery.
3. **disruption-recovery + ancillary-service** (wave 17) —
   disruption-recovery consumes dispatch signals; ancillary is independent.
4. **transfer-management** (wave 18) — deepest coupling to journey/order
   structures; benefits from everything before it.

Skeleton languages are kept as-is (waitlist: Rust). Every activation
follows the established pipeline: contract documents first (gated
field-by-field), then service implementation on the persistence baseline
(snapshots + outbox + durable dedup + OTel), then cross-service
integration, then loadgen journeys and a dedicated e2e script.

## Consequences

- The sold-out flood stops being pure failure noise in wave 15; capacity
  release events gain a consumer with real business meaning.
- Each wave leaves the 12-restart certification and DLQ zero-baseline
  intact — activation tasks must not regress them.
