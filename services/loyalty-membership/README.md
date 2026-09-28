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

## Membership programme

The membership programme is member relations' export, and this release is built
against one revision of it: the revision the settings were taken from, the names the
export publishes and the namespaces an enrolment's account reference may arrive in are
the programme's decisions. They are stated in
[`config/membership-programme.json`](config/membership-programme.json), which the
image carries at `/app/config` and `src/programme.ts` reads once.

`accountReference.acceptedNamespaces` is the statement an enrolment's account
reference is held to. The account contract this platform publishes puts its account
references in the `acct` namespace, so the programme states that namespace, and the
statement is held to where the membership is keyed.
