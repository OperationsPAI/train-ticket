# admin-audit

Domain: Admin & Audit

Language: java

Phase: phase-1-support

Status: implemented and deployed (Helm revision 521; see [current status](../../docs/00-current-status.md))

## Owns

- OperatorIdentity
- Approval
- ManualAction
- AuditTrail

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `admin_audit` on the `core` PostgreSQL instance
  (`services.admin-audit.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`,
`/livez`, `/ready`, `/metadata`.

## HTTP surface

- `POST /api/v1/admin/operators`, `GET /api/v1/admin/operators/{operatorId}`.
- `POST /api/v1/admin/manual-actions` plus `{id}/approve`, `{id}/reject`.
- `GET /api/v1/admin/audit-trail`.

The full contract lives in
[`docs/08-contracts/api/admin-audit.md`](../../docs/08-contracts/api/admin-audit.md).

## Events

Publishes `OperatorRegistered`, `ManualActionRequested`, `ManualActionApproved`,
`ManualActionRejected` and `ManualActionExecuted`; see
[`docs/08-contracts/events/admin-audit.md`](../../docs/08-contracts/events/admin-audit.md).
Consumes `events:customer-service`
(`adapters/messaging/AdminAuditSubscriptionRunner.java`).

## Metrics

Publishes the Java kit's process-level transport series; it owns no business
metric in
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Build and test

```bash
cd services/admin-audit && mvn -o test
```

Requires JDK 25 (the repo's devcontainer / `maven:3.9-eclipse-temurin-25`
image).

## DDD Sources

- `docs/02-domains/admin-audit.md`

## Language Rationale

Java is a conservative fit for approvals, audit retention, and authorization boundaries.
