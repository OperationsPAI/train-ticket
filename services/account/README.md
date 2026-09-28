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

## Admission

`POST /api/v1/accounts` is the only unauthenticated write on the platform, and
every journey begins with it. It is admitted on a per-client budget stated in
`config/registration-admission.json` and counted in the service's own store, so
that one client's rate cannot become the platform's rate.

The budget is a rate rather than a queue: a client that has spent its window is
answered instead of waiting, and the caller decides whether to come back. What
the deployment admits is the numbers in that file, not the code that reads it.


## Settings

The service is configured by the release and by its own settings, read at
startup rather than baked into the image:

- `config/registration-admission.json` -- the new-account admission: the window
  it is counted over, the allowance one client has inside it, and the header the
  edge names the client in. The image copies it to `/app/config`; the service
  fails to start rather than admit everything if it is missing.
- The rest of the runtime wiring is the release's: `DATABASE_URL`, `REDIS_URL`
  and `PORT` come from the chart's `services.account.env`.
