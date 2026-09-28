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

## Registration admission

`POST /api/v1/accounts` is the only write the platform answers before a caller
holds an account, so one client's retry rate would otherwise be the platform's
rate. The endpoint therefore carries a per-client budget: the number of new
accounts one client has asked for inside a window is counted in the service's
own store (`registration_admissions`, migration 004), so the allowance holds
across replicas, and a client that has spent its window is answered
`429 TOO_MANY_REQUESTS` until the next one starts.

The numbers are the deployment's statement rather than the code's, and live in
[`config/registration-admission.json`](config/registration-admission.json):
`windowSeconds`, `maxNewAccountsPerClient`, and the header the edge names the
client in (`clientHeader`). The image copies that file to `/app/config` beside
the service, and the service reads it once at startup -- a settings file that
cannot be read is a boot failure rather than a service that admits everything.
Changing how many new accounts a client may open changes that file and nothing
else.

## DDD Sources

- `docs/02-domains/account.md`

## Language Rationale

TypeScript is suitable for identity/session edge APIs while traveler facts remain in Traveler Profile.
