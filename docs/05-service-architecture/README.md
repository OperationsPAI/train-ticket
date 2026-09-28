# Service Architecture

Updated: September 28, 2026.

Services own their business state and collaborate through HTTP commands, queries and domain events.
The deployment baseline and release evidence are recorded in [current project status](../00-current-status.md).
[TODO](../TODO.md) is the shared source for unfinished capabilities, work in progress and acceptance gaps.

| Document | Purpose |
|---|---|
| [Languages and source paths](language-selection.md) | Catalog entries checked against service directories, build manifests and API source. |
| [Domain ownership](../03-ddd-final/domain-reduce-status.md) | Business owners and registered API entry points. |
| [Cross domain decisions](../03-ddd-final/decision-record.md) | Ownership and collaboration rules. |
| [Business collaboration](../03-ddd-final/phase-1-contract.md) | Purchase, post sales, compensation and financial routing. |
| [Change routing](../03-ddd-final/change-routing.md) | Service selection for a requested business change. |

[service-catalog.json](../../service-catalog.json) records service identity, path and language.
Catalog coverage is enforced by `make check`; the release record is in [current status](../00-current-status.md).
[project-index.yaml](../../project-index.yaml) records requirement references.
Actual HTTP routes are defined by each service's registered controllers and handlers.
Shared runtime libraries live under [platform](../../platform/).
PostgreSQL stores business data and transactional messaging records; Redis Streams transports events between services.

Payment Channel and Invoicing are independent services.
Seat Assignment uses Go, Trip Planning uses Rust, and Identity Verification and Risk & Compliance use Python.
Capacity's combined supply implementation and Notification's durable delivery queue are deployed.
Their remaining work is tracked in [TODO](../TODO.md).
