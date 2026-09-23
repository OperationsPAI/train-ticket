# Service Architecture

Updated: September 24, 2026.

Services own their business state and collaborate through HTTP commands, queries and domain events.
The documented deployment baseline is Helm revision 279; release evidence is recorded in [current project status](../00-current-status.md).
[TODO](../TODO.md) is the shared source for unfinished capabilities, work in progress and acceptance gaps.

| Document | Purpose |
|---|---|
| [Languages and source paths](language-selection.md) | Catalog entries checked against service directories, build manifests and API source. |
| [Domain ownership](../03-ddd-final/domain-reduce-status.md) | Business owners and registered API entry points. |
| [Cross domain decisions](../03-ddd-final/decision-record.md) | Ownership and collaboration rules. |
| [Business collaboration](../03-ddd-final/phase-1-contract.md) | Purchase, post sales, compensation and financial routing. |
| [Change routing](../03-ddd-final/change-routing.md) | Service selection for a requested business change. |

[service-catalog.json](../../service-catalog.json) records service identity, path and language.
Catalog coverage differences are tracked in [TODO T10](../TODO.md#t10-reconcile-repository-metadata-and-static-checks).
[project-index.yaml](../../project-index.yaml) records requirement references.
Actual HTTP routes are defined by each service's registered controllers and handlers.
Shared runtime libraries live under [platform](../../platform/).
PostgreSQL stores business data and transactional messaging records; Redis Streams transports events between services.

Payment Channel and Invoicing are independent services.
Seat Assignment uses Go, Trip Planning uses Rust, and Identity Verification and Risk & Compliance use Python.
Capacity's new combined supply implementation is uncommitted and undeployed.
Notification's durable delivery queue is WIP and undeployed.
Their completion and deployment work is tracked in [TODO](../TODO.md).
