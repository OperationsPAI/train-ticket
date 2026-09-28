# account

Domain: Account

Language: typescript

Phase: phase-1-limited

Status: skeleton

## Owns

- UserAccount
- Session
- Preference
- Freeze
- AccountClosureSaga shell

## DDD Sources

- `docs/02-domains/account.md`

## Language Rationale

TypeScript is suitable for identity/session edge APIs while traveler facts remain in Traveler Profile.

## Skeleton Check

```bash
npm test
```

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
