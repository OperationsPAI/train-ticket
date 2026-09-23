# Train Ticket

Train Ticket implements purchase, reservation, payment, ticketing, post sales and supporting travel services across Java, Go, Python, Rust and TypeScript.
Business implementations live in `services/`, shared infrastructure in `platform/`, and image and Kubernetes definitions in `deploy/`.

## Documentation

| Entry | Purpose |
|---|---|
| [Current status](docs/00-current-status.md) | Deployed release, verified outcomes and workspace boundaries. |
| [TODO](docs/TODO.md) | The single list of remaining defects, incomplete implementations and acceptance work. |
| [Documentation index](docs/README.md) | Domain design, HTTP APIs, events and operations. |
| [Cross domain design](docs/03-ddd-final/README.md) | Ownership and business collaboration. |
| [Service architecture](docs/05-service-architecture/README.md) | Service language and implementation mapping. |
| [Deployment guide](docs/deployment.md) | Helm profiles and operational checks. |
| [Loadgen guide](deploy/loadgen-go/README.md) | Resident workload, checkpoints and verification. |

`service-catalog.json` supplies service identifiers, directories and languages.
The Helm chart supplies deployed workloads and exact image references.
Catalog phase labels and `project-index.yaml` requirement identifiers are traceability metadata, not business acceptance evidence.

## Development and verification

The [development container](.devcontainer/README.md) provides the language toolchains.
Repository checks are exposed by the Makefile:

```bash
make check
make check-strict
```

Service README files describe module tests and the recorded data or local infrastructure required by integration tests.
Live loadgen tests call actual service APIs and retain created business records.
Passing module or focused live tests does not establish complete resident peak acceptance.

## Deployment boundaries

The resident release uses `deploy/helm/train-ticket` and digest pinned service images.
Preserve its mounted loadgen configuration and `loadgen-resident-state` claim when updating images.
The source worktree, committed code and deployed image set can differ; consult current status before building or deploying a service.
The coordinated Capacity supply calculation and notification delivery queue remain unpublished workspace changes at this handoff.
