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

## HTTP surface

`/api/v1/support-cases`: create, read, `classify`, `assign`, `escalate`,
`request-escalation`, `evidence`, `resolve`, `reopen`, `close`,
`manual-action-requests`, `compensation-offers`; plus
`/api/v1/compensation-offers/{id}/accept|issue` and
`POST /api/v1/support-cases/evaluate-sla`.

The full contract lives in
[`docs/08-contracts/api/customer-service.md`](../../docs/08-contracts/api/customer-service.md).

The support desk behind this deployment staffs the web, phone, chat, mail and
in-app message queues; the mobile app, the bot and the operator console file
through the queues that own them. Which intake channels that leaves the service
answering is in `config/intake-channels.json`, the settings the image ships
beside the process.

## Events

Publishes `SupportCaseOpened`, `EvidenceAttached`, `SupportCaseClassified`,
`SupportCaseAssigned`, `SupportCaseEscalated`, `SupportCaseResolved`,
`SupportCaseClosed` and `SupportCaseReopened`; see
[`docs/08-contracts/events/customer-service.md`](../../docs/08-contracts/events/customer-service.md).
Consumes `events:journey-order`, `events:post-sales`, `events:admin-audit`,
`events:transfer-management` and `events:identity-verification`
(`src/adapters/messaging/stream-config.ts`).

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
