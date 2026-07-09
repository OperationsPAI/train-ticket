# Cross-Context Contracts

This directory defines the shared contracts that bounded contexts must follow
when exchanging events, commands, or shared value objects. These contracts are
the normative reference for field-by-field conformance.

## Contents

This document set is the **single source of truth** for every cross-context
message exchanged between bounded contexts in the Train Ticket platform. Every
service must conform to these contracts when communicating across bounded
context boundaries. Integration testing (联调) validates against these contracts.

## Organization

```
docs/08-contracts/
├── README.md               ← This file: organization, versioning, conformance
├── shared-primitives.md    ← Language-neutral field-level definitions
├── conformance-gaps.md     ← Known mismatches between spec and implementation
└── events/
    ├── admin-audit.md
    ├── customer-service.md
    ├── trip-planning.md
    ├── offer-management.md
    ├── journey-order.md
    ├── booking-orchestration.md
    ├── capacity-availability.md
    ├── fare-pricing.md
    ├── payment.md
    ├── provider-integration.md
    ├── entitlement-ticketing.md
    ├── post-sales.md
    ├── notification.md
    ├── traveler-profile.md
    ├── place-network.md
    ├── waitlist.md
    ├── dispatch.md
    ├── wallet-promotion.md
    ├── seat-assignment.md
    └── finance-settlement-events.md
```

## Versioning

| Concept | Convention |
|---|---|
| `shared-primitives.md` | Cross-context IDs, Money, timestamps, event envelope. |
| `events/` | Per-domain event payload contracts. |

## Principles

1. Every event payload must conform to the contract in `events/`.
2. Cross-context IDs, Money, and timestamps must use the shapes defined in
   `shared-primitives.md`.
3. If a needed event is missing from the contracts, extend the contract in
   this directory rather than inventing a private shape.
