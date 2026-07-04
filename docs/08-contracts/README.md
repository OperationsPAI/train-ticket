# Cross-Domain Contract Specification

Last updated: 2026-06-28

## Purpose

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
    └── place-network.md
```

## Versioning

| Concept | Convention |
|---|---|
| Schema version | Each event definition has a `schemaVersion` field (positive integer). |
| Backward-compatible change | Adding an optional field: increment minor schema version. |
| Breaking change | Removing, renaming, or making required a previously optional field: increment major schema version. |
| Event envelope | The `EventEnvelope` wrapper always carries `schemaVersion`. Consumers MUST reject messages with an unsupported major version. |
| Contract document version | Each file header carries a `Last updated` date. Git history is the authoritative change log. |

## Conformance rules

Every service MUST:

1. **Publish events** using the `EventEnvelope` structure defined in
   `shared-primitives.md`.
2. **Use canonical ID formats** from `shared-primitives.md`. Where a service
   deviates, the deviation MUST be listed in `conformance-gaps.md`.
3. **Serialize JSON fields in camelCase** (e.g. `eventType`, `occurredAt`).
4. **Serialise enum values as SCREAMING_SNAKE_CASE** (e.g. `CAPTURED`, `CONFIRMED`).
5. **Use UTC RFC3339 (ISO-8601) timestamps** with a trailing `Z` for all
   date-time fields. Example: `2026-07-03T10:30:00Z`.
6. **Represent money as `{ "currency": "CNY", "minorUnits": 12345 }`** — never use
   floating-point amounts.
7. **Respect idempotency keys** where specified. Duplicate delivery of the same
   `eventId` MUST NOT cause duplicate side effects.
8. **Maintain causation chains**: every event MUST carry `causationId` pointing
   to the command or event that caused it, and `correlationId` linking all
   events in the same business transaction.
9. **Consume only events from other contexts** that are listed in the consumer's
   event file. Undocumented consumption is a conformance gap.
10. **Publish only events** listed in the publisher's event file. Undocumented
    publication is a conformance gap.

## Conformance checklist for coders and reviewers

### Before merging any cross-context event change:

- [ ] Is the event type name added to the publishing context's event file?
- [ ] Is the payload documented field-by-field with type, requiredness, and description?
- [ ] Is the consumer listed in the event definition's `consumers` field?
- [ ] Is the consumer's event file updated to list the consumed event?
- [ ] Does `shared-primitives.md` cover any new ID type or value object shape?
- [ ] Is the `schemaVersion` incremented appropriately?
- [ ] Is `conformance-gaps.md` updated with any temporary deviation?
- [ ] Does the service implementation use the canonical ID format and Money type?
- [ ] Are all JSON fields in camelCase and enums in SCREAMING_SNAKE?
- [ ] Are all timestamps in UTC RFC3339 with trailing Z?

### For new services or new bounded contexts:

- [ ] Does the service have its own event file under `events/`?
- [ ] Are all published events listed with producer, consumers, trigger, payload?
- [ ] Are all accepted commands listed with sender, payload, and expected response?

## Serialisation conventions

| Concern | Convention |
|---|---|
| JSON field naming | camelCase (e.g. `offerId`, `amountMinor`) |
| Enums | SCREAMING_SNAKE (e.g. `CAPTURED`, `PENDING_PAYMENT`) |
| Date-time | UTC RFC3339 with `Z` suffix (e.g. `2026-07-03T10:30:00Z`) |
| Money | `{ "currency": "CNY", "minorUnits": 12345 }` (minorUnits is integer, never float) |
| IDs | String with prefix convention (see `shared-primitives.md`) |
| Optional fields | `null` or absent — consumers MUST handle both |
| Event wrapper | `EventEnvelope` (see `shared-primitives.md`) |
