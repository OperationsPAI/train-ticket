# Train Ticket DDD Greenfield

This branch is a clean-slate rebuild of Train Ticket from the accepted DDD
design documents. The legacy `ts-*` services, root Maven reactor, Helm charts,
Skaffold setup, and old CI workflows have been removed intentionally.

## Source Of Truth

- `docs/00-current-status.md` is the current project-status entry point for the
  full rewrite / greenfield initialization.
- `docs/03-ddd-final/` contains the accepted high-level DDD baseline.
- `docs/02-domains/` contains the detailed bounded-context documents.
- `docs/05-service-architecture/` records the initial polyglot service
  skeleton and language assignment.
- `service-catalog.json` is the machine-readable service/domain/language map.
- `project-index.yaml` is retained for skeleton validation and historical
  requirement traceability. Its old `WP-01` / `WP-xx` entries are legacy
  references, not the current execution backlog.
- `docs/04-implementation-plan/status.md` is a superseded historical AgentM
  loop record, not the current blocker list.

## Service Skeleton

The repository now initializes one service skeleton per DDD bounded context:

- Java + Maven/Spring Boot: transactional aggregates, money, post-sales,
  profile, audit, and finance services.
- Go + Gin: provider adapters, place/service-plan lookup, fulfillment
  ingestion, supplier catalog, and future dispatch.
- Python + uv/FastAPI: pricing rules, trip planning, risk, reporting, and
  future recovery or transfer logic.
- Rust + Cargo/Axum: shared-kernel reference, capacity, entitlement, and future
  waitlist invariants.
- TypeScript + npm/Fastify: offer, notification, account, customer-service, and
  ancillary API/workflow services.

Run the quick adaptive host skeleton check with:

```bash
make check
```

Run the authoritative full validation in the devcontainer or CI with:

```bash
.devcontainer/scripts/check.sh
make check-strict
```

## Implementation Policy

- New code must be created from the DDD documents, not by modifying legacy
  service code.
- Legacy behavior may be used only as reference material outside this branch or
  through a future Legacy ACL / Strangler work package.
- Shared Kernel must stay minimal: IDs, value objects, event envelope, and
  cross-context primitives only.
- Domain event payloads belong to their owning bounded context. Shared examples
  must remain test-only and non-normative.
- New implementation slices should pass brief/review before code is added.
- Do not resume the old `WP-01` / `WP-xx` sequence as the current plan. If work
  packages are useful, regenerate them from the current rewrite priorities and
  explicitly approve them before implementation.

## Current Next Step

Initialize the rewrite plan from the current DDD baseline and skeleton state.
The immediate task is not to revise old `WP-01`; it is to define a fresh,
minimal implementation slice for the full rebuild.

The skeleton exists so agents can route future implementation work to the
correct bounded context. It does not mean any old WP business behavior is
complete or currently scheduled.

## Development Container

The devcontainer is the standard development environment for this rewrite and
for full validation. See `.devcontainer/README.md`. Host `make check` is useful
as a quick smoke test, but the devcontainer/CI `make check-strict` flow is the
authoritative gate.
