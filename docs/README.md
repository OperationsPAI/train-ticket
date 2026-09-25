# Train Ticket documentation

Last updated: 2026-09-24.

## Current entry points

The [current project status](00-current-status.md) records verified runtime behavior and deployment boundaries.
The [TODO](TODO.md) is the single active list of remaining defects, unfinished implementation and acceptance work.
The [loadgen guide](./README.md) describes resident configuration, persistence, operation recovery and live test commands.
The [standing capacity policy](08-contracts/standing-capacity-policy.md) defines the versioned quota used by Seat Assignment.
The [capacity integration design](10-domain-enrichment/capacity-overbooking.md) distinguishes the current Hold path from the unpublished coordinated supply implementation.
The [durable event recovery contract](08-contracts/durable-event-recovery.md) defines the bounded PostgreSQL replay source, consumer checkpoints, durable dead-letter evidence and terminal compensation mismatch handling.

## Reference areas

| Area | Location | Scope |
|---|---|---|
| HTTP APIs | `08-contracts/api/` | Request fields, response fields, errors and business operation semantics. |
| Events | `08-contracts/events/` | Published payloads, consumed events and delivery expectations. |
| Shared primitives | `08-contracts/shared-primitives.md` | Identifiers, money, timestamps and shared field formats. |
| Persistence | `08-contracts/persistence.md` | Snapshot, transaction, outbox and replay requirements. |
| Domain details | `02-domains/` | Domain ownership, aggregates and business invariants. |
| Cross domain design | `03-ddd-final/` | Domain boundaries, decisions and change routing. |
| Observability | `07-observability/` | Collectors, tracing, metrics and runtime integration. |
| Performance evidence | `09-performance/` | Dated measurements and performance investigations. |
| Domain implementation | `10-domain-enrichment/` | Implementation descriptions and integration requirements. |
| Service operation | `services/<service>/README.md` from the repository root | Runtime configuration and executable tests for each service. |

## Documentation accuracy

Use service implementations and recorded verification evidence when updating behavior descriptions.
Record whether a change is committed, deployed or verified online.
Unpublished Notification and Capacity code is retained in the worktree at this handoff and is explicitly marked in the corresponding design documents.
Dated performance reports describe their measurement window.
Passing a focused test does not establish a complete loadgen acceptance result.
An interface or policy change includes updates to its API or event documentation, service README and relevant operating instructions.
