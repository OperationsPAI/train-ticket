# Identity Verification

Python FastAPI service implementing credential registration, deterministic SIM verification, eligibility certificate lookup, pre-order checks, and purchase-limit fact emission for REQ-141.

Status: implemented and deployed (Helm revision 521; see [current status](../../docs/00-current-status.md)).

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `identity_verification` on the `user` PostgreSQL instance
  (`services.identity-verification.db` and `postgres.instances.user.databases`
  in `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`, `/ready`,
`/metadata`.

## HTTP surface

All routes are under `/api/v1/identity-verification`: `verifications`,
`verification-cases` (plus `{id}`, `{id}/manual-override`), `credentials` and
`credentials/{id}/verification-status`, `eligibility-certificates`,
`pre-order-checks` (plus `{id}/confirm`, `{id}/release`) and
`purchase-limit-facts/{id}/mark-failed|mark-missed`.

The full contract lives in
[`docs/08-contracts/api/identity-verification.md`](../../docs/08-contracts/api/identity-verification.md).

## Events

Publishes `CredentialRegistered`, `VerificationCaseStarted`, `VerificationPassed`,
`VerificationFailed`, `EligibilityCertificateRegistered` and the eligibility
usage facts to `events:identity-verification`
(`src/identity_verification/adapters/messaging/__init__.py`); see
[`docs/08-contracts/events/identity-verification.md`](../../docs/08-contracts/events/identity-verification.md).
Consumes `events:traveler-profile`, `events:journey-order` and
`events:risk-compliance`.

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Build and test

```bash
cd services/identity-verification && PYTHONPATH=src:../../platform/python-kit/src python3 -m pytest -q
```

Recorded PostgreSQL and Redis tests skip when `TEST_DATABASE_URL` /
`TEST_REDIS_URL` are unset.
