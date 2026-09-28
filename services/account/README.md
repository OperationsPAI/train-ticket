# account

Domain: Account

Language: typescript

Phase: phase-1-account-domain-foundation

Status: implemented and deployed (Helm revision 521; see [current status](../../docs/00-current-status.md))

## Owns

- UserAccount
- Session
- Preference
- Freeze
- AccountClosureSaga shell

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `account` on the `user` PostgreSQL instance
  (`services.account.db` and `postgres.instances.user.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`,
`/livez`, `/ready`, `/metadata`.

## HTTP surface

- `POST /api/v1/accounts`, `GET /api/v1/accounts/{accountId}`.
- `POST /api/v1/accounts/{accountId}/freeze|unfreeze|start-closure`.
- `PATCH /api/v1/accounts/{accountId}/preferences`.

The full contract lives in
[`docs/08-contracts/api/account.md`](../../docs/08-contracts/api/account.md).

## Events

Publishes `AccountCreated`, `AccountFrozen`, `AccountUnfrozen`,
`AccountClosureStarted`, `AccountClosed`, `SessionOpened`, `SessionRevoked`
and `PreferenceUpdated` to `events:account`; see
[`docs/08-contracts/events/account.md`](../../docs/08-contracts/events/account.md).
It consumes no inbound event stream.

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Build and test

```bash
cd services/account && npm test
```

`npm test` compiles TypeScript into the ignored `test-output/` directory and
runs the Node built-in test suite. Live tests that need PostgreSQL or Redis
skip when `TEST_DATABASE_URL` / `TEST_REDIS_URL` are unset.

## DDD Sources

- `docs/02-domains/account.md`

## Language Rationale

TypeScript is suitable for identity/session edge APIs while traveler facts remain in Traveler Profile.
