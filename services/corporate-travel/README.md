# corporate-travel

Domain: Corporate Travel
Language: Python
Status: REQ-215 service foundation

Corporate Travel owns corporate agreements, authorized traveler lists, corporate billing accounts, and monthly settlement periods. This slice supports agreement creation/activation, traveler authorization, order/payment fact aggregation, and billing-period closure events.

When `DATABASE_URL` is configured, startup applies migrations and wires the owning `/api/v1/agreements` API to PostgreSQL. Agreement, authorization, policy-check budget, billing and outbox state survive process restarts. POST idempotency responses use the same PostgreSQL database, so replaying a command with the same UUID-v7 key after restart returns the original result instead of creating another agreement or authorization.

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `corporate_travel` on the `core` PostgreSQL instance
  (`services.corporate-travel.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`, `/ready`,
`/metadata`.

## HTTP surface

`/api/v1/agreements`: create, read, `{id}/authorize`,
`{id}/monthly-invoices`, `{id}/policy-checks`.
The full contract lives in
[`docs/08-contracts/api/corporate-travel.md`](../../docs/08-contracts/api/corporate-travel.md).

## Events

Publishes `CorporateAgreementCreated`, `CorporateAgreementActivated` and the
authorization/policy/billing-period facts to `events:corporate-travel`; see
[`docs/08-contracts/events/corporate-travel.md`](../../docs/08-contracts/events/corporate-travel.md).
Consumes `events:journey-order`, `events:payment` and `events:post-sales`
(`src/corporate_travel/messaging.py`).

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Local validation

```bash
cd services/corporate-travel && PYTHONPATH=src:../../platform/python-kit/src python3 -m pytest -q
uvicorn corporate_travel.api:create_app --factory --host 127.0.0.1 --port 8080
curl http://127.0.0.1:8080/health
```

PostgreSQL-backed tests skip when `TEST_DATABASE_URL` is unset.
