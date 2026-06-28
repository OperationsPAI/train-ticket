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
