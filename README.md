# Train Ticket DDD Greenfield

This branch is a clean-slate rebuild of Train Ticket from the accepted DDD
design documents. The legacy `ts-*` services, root Maven reactor, Helm charts,
Skaffold setup, and old CI workflows have been removed intentionally.

## Source Of Truth

- `docs/03-ddd-final/` contains the accepted high-level DDD baseline.
- `docs/02-domains/` contains the detailed bounded-context documents.
- `docs/04-implementation-plan/status.md` records the first AgentM loop result
  and the immediate blockers before implementation.

## Implementation Policy

- New code must be created from the DDD documents, not by modifying legacy
  service code.
- Legacy behavior may be used only as reference material outside this branch or
  through a future Legacy ACL / Strangler work package.
- Shared Kernel must stay minimal: IDs, value objects, event envelope, and
  cross-context primitives only.
- Domain event payloads belong to their owning bounded context. Shared examples
  must remain test-only and non-normative.
- Each work package should pass brief/review before code is added.

## Current Next Step

Revise and approve `WP-01` before implementation:

1. Define the exact Phase 1 event-envelope coverage matrix.
2. Decide how Provider Integration is represented: ACL mapping coverage only,
   or a clearly owned normalized/archived provider fact.
3. Keep future-scope domains out of WP-01 acceptance evidence.
4. Specify `EventMetadata` UUID representation, validation boundary, and JSON
   round-trip behavior.

After WP-01 is approved, implement the smallest Shared Kernel slice with tests.

## Development Container

The devcontainer is retained as a neutral AgentM/ARL tool image. It does not
define the application stack. See `.devcontainer/README.md`.
