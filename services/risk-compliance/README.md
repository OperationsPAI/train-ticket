# risk-compliance

Domain: Risk & Compliance

Language: python

Phase: phase-1-support

Status: implemented and deployed (Helm revision 521; see [current status](../../docs/00-current-status.md))

## Owns

- RiskAssessment
- Challenge
- BlockDecision
- EvidenceSummary

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `risk_compliance` on the `event` PostgreSQL instance
  (`services.risk-compliance.db` and `postgres.instances.event.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`, `/ready`,
`/metadata`.

## HTTP surface

- `POST/GET /api/v1/risk-assessments` and `/api/v1/risk-assessments/{id}`.
- `POST/GET /api/v1/risk-evaluations` and `/api/v1/risk-evaluations/{id}` plus
  `{id}/override`; `POST /api/v1/risk-blocks/lift`.

The full contract lives in
[`docs/08-contracts/api/risk-compliance.md`](../../docs/08-contracts/api/risk-compliance.md).

## Events

Publishes to `events:risk-compliance`; the event types and consumers are in
[`docs/08-contracts/events/risk-compliance.md`](../../docs/08-contracts/events/risk-compliance.md).
Consumes `events:journey-order`, `events:booking-orchestration` and
`events:identity-verification`
(`src/risk_compliance/adapters/messaging/redis_streams.py`).

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Build and test

```bash
cd services/risk-compliance && PYTHONPATH=src:../../platform/python-kit/src python3 -m pytest -q
```

Recorded PostgreSQL and Redis tests skip unless their
`TEST_DATABASE_URL` / `TEST_REDIS_URL` (and sample) variables are set.

## DDD Sources

- `docs/02-domains/risk-compliance.md`

## Language Rationale

Python supports rule experimentation, scoring, and evidence summarization without coupling source domains.
