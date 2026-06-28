# Current Project Status

Last updated: 2026-06-28

## Status

This repository is being prepared for a full rewrite / greenfield rebuild of the
Train Ticket system.

The current tree is a greenfield skeleton: DDD reference documents, a service
catalog, per-bounded-context service skeletons, and a devcontainer. It is not a
partially completed implementation of the old Train Ticket services.

## Authoritative Current Framing

- Build new code from the accepted DDD design and current rewrite decisions.
- Treat the old `WP-01` / `WP-xx` work-package plan as legacy planning material.
  It must not be used as the current execution backlog unless it is explicitly
  regenerated and re-approved for the rewrite.
- Treat `project-index.yaml` entries `REQ-101` through `REQ-123` as legacy
  references, not active implementation requirements.
- Treat `docs/04-implementation-plan/status.md` as a superseded historical
  AgentM loop record, not as the current blocker list.
- Legacy service behavior may be consulted only as reference material or through
  a future Legacy ACL / strangler strategy; it must not be copied forward as the
  implementation source of truth.

## Current Source Of Truth

Use these documents to understand the intended domain model and skeleton layout:

1. `docs/03-ddd-final/` — accepted DDD baseline and decision records.
2. `docs/02-domains/` — bounded-context details.
3. `docs/05-service-architecture/` — service skeleton and language assignment.
4. `service-catalog.json` — machine-readable service/domain/language map.
5. This file — current project-status framing for the rewrite.

When a new implementation plan is needed, create it from the current DDD baseline
and the actual rewrite priorities instead of resuming the legacy WP sequence.

## Development Environment

The standard development environment is the configured devcontainer. It contains
the Java, Go, Python, Rust, TypeScript, and operational tools needed for full
skeleton validation.

Use host checks only as a quick adaptive smoke test:

```bash
make check
```

Use the devcontainer or CI for authoritative validation:

```bash
.devcontainer/scripts/check.sh
make check-strict
```

`make check` may skip language checks when host tools are missing. `make
check-strict` must not skip required toolchains and is the validation gate for
container/CI work.
