# customer-service

Domain: Customer Service

Language: typescript

Phase: phase-1-support

Status: implemented and deployed (Helm revision 521; see [current status](../../docs/00-current-status.md))

## Owns

- SupportCase
- EvidenceRef
- ManualActionRequest
- CaseTimeline

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `customer_service` on the `core` PostgreSQL instance
  (`services.customer-service.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`,
`/livez`, `/ready`, `/metadata`.

## Settings

- The queues the desk's consoles poll are stated by the release
  (`services.customer-service.env`): a desk that opens a lane names it here
  rather than waiting for a build, and a handoff to a lane the release does not
  name is refused. A release that names no queue keeps the arrangement the
  service ships.

## HTTP surface

`/api/v1/support-cases`: create, read, `classify`, `assign`, `escalate`,
`request-escalation`, `evidence`, `resolve`, `reopen`, `close`,
`manual-action-requests`, `compensation-offers`; and
`POST /api/v1/support-cases/evaluate-sla`, plus
`/api/v1/compensation-offers/{id}/accept|issue`.

The full contract lives in
[`docs/08-contracts/api/customer-service.md`](../../docs/08-contracts/api/customer-service.md).

## Events

Publishes `SupportCaseOpened`, `EvidenceAttached`, `SupportCaseClassified`,
`SupportCaseAssigned`, `SupportCaseEscalated`, `SupportCaseResolved`,
`SupportCaseClosed` and `SupportCaseReopened`; see
[`docs/08-contracts/events/customer-service.md`](../../docs/08-contracts/events/customer-service.md).
Consumes five streams -- `events:journey-order`, `events:post-sales`,
`events:admin-audit`, `events:transfer-management` and
`events:identity-verification` (`src/adapters/messaging/stream-config.ts`).

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Build and test

```bash
cd services/customer-service && npm test
```

`npm test` compiles TypeScript into the ignored `test-output/` directory and
runs the Node built-in test suite. The PostgreSQL test skips when
`TEST_DATABASE_URL` is unset.

## DDD Sources

- `docs/02-domains/customer-service.md`

## Language Rationale

TypeScript fits collaborative case APIs and UI-adjacent timelines without owning target state.
