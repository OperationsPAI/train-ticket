# transfer-management

Deployed service (Helm revision 521; see [current status](../../docs/00-current-status.md)).
The catalog still carries the activation labels `future-scope` /
`placeholder-skeleton` as metadata, but the service is built, deployed and part
of the running release.

Domain: Transfer Management

Language: python

Phase: future-scope

Status: placeholder-skeleton

## Owns

- ConnectionContract
- MctVersion
- ProtectedConnection
- TransferPlan
- Connection runtime state
- TransferRiskPolicy

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `transfer_management` on the `core` PostgreSQL instance
  (`services.transfer-management.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`, `/ready`,
`/metadata`.

## HTTP surface

- `/api/v1/transfer-plans` create/read plus `{id}/evaluate`, `{id}/refresh`,
  `{id}/expire`.
- `/api/v1/connections`, `/api/v1/connection-contracts` (with
  `{id}/confirm`, `{id}/withdraw`).
- `/api/v1/mct-rules` CRUD plus `{id}/publish`, `{id}/retire`;
  `/api/v1/risk-policies`, `/api/v1/segment-status-reports`.

The full contract lives in
[`docs/08-contracts/api/transfer-management.md`](../../docs/08-contracts/api/transfer-management.md).

## Events

Publishes to `events:transfer-management`; the event types and consumers are in
[`docs/08-contracts/events/transfer-management.md`](../../docs/08-contracts/events/transfer-management.md).
Consumes `events:disruption-recovery`, `events:fulfillment` and
`events:trip-planning`
(`src/transfer_management/adapters/messaging/__init__.py`).

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Build and test

```bash
cd services/transfer-management && PYTHONPATH=src:../../platform/python-kit/src python3 -m pytest -q
```

Live tests that need PostgreSQL or Redis skip when the corresponding
`TEST_DATABASE_URL` / `TEST_REDIS_URL` variables are unset.

## DDD Sources

- `docs/02-domains/transfer-management.md`

## Language Rationale

Python fits connection-risk calculation and later protected-transfer recovery planning.
