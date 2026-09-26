# Loyalty Membership

TypeScript service for membership tier state, points ledger entries and lots,
rolling tier evaluation, expiry and points redemption through Wallet / Promotion.

## Membership programme

The programme member relations exports moves on its own cadence, and this service
is released against one revision of it. The namespaces an enrolment's account
reference may arrive in belong to the programme rather than to the callers that
send them, so they are stated in
[`config/membership-programme.json`](config/membership-programme.json), which the
image carries at `/app/config`, and read once at startup by `src/programme.ts`.

`accountReference.acceptedNamespaces` is the statement an enrolment's account
reference is held to; an empty statement accepts the reference as the caller sends
it.

## Local validation

```bash
npm test
```
