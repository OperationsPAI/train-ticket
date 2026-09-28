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

## Skeleton Check

```bash
npm test
```
