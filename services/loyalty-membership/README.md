# Loyalty Membership

TypeScript service for membership tier state, points ledger entries and lots,
rolling tier evaluation, expiry and points redemption through Wallet / Promotion.

Status: implemented and deployed (Helm revision 521; see [current status](../../docs/00-current-status.md)).

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `loyalty_membership` on the `core` PostgreSQL instance
  (`services.loyalty-membership.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`, `/ready`,
`/metadata`.
- The membership programme in `config/membership-programme.json`, carried by the
  image at `/app/config` and read once at startup by `src/programme.ts`: the
  poles and what each asks for,
  the pole a membership opens at, the names the member-relations export uses and
  the account-reference namespaces an enrolment keys by.

## HTTP surface

- `/members/enroll`, `/members/{memberId}`, `/members/by-account/{accountId}`.
- `/members/{memberId}/evaluate-tier`, `/members/{memberId}/expire-points`,
  `/members/{memberId}/redeem`, `/members/{memberId}/redeem-ticket`.
- `POST /internal/payments/{paymentIntentId}/reconcile`.

The reconciliation endpoint is documented in
[`docs/08-contracts/api/loyalty-reconciliation.md`](../../docs/08-contracts/api/loyalty-reconciliation.md);
there is no `docs/08-contracts/api/loyalty-membership.md` yet, so the
`/members` controllers are the contract of record.

## Events

Publishes `PointsEarned`, `PointsRedeemed`, `PointsRestored`, `PointsExpired`,
`MemberTierUpgraded`, `MemberTierDowngraded` and `TierEvaluationCompleted` on
`events:loyalty-membership` (producer `loyalty-membership` in `src/ports.ts`).
Consumes `events:journey-order`, `events:payment` and `events:post-sales`
(`src/bootstrap.ts`).

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Local validation

```bash
cd services/loyalty-membership && npm test
```

Live tests that need PostgreSQL or Redis skip when `TEST_DATABASE_URL` /
`TEST_REDIS_URL` are unset.
