# Loyalty Membership

TypeScript service for membership tier state, points ledger entries and lots,
rolling tier evaluation, expiry and points redemption through Wallet / Promotion.

## Membership programme

The poles and what each of them asks for, the pole a membership opens at, the
names the member-relations export uses, the redemption rule and the namespaces an
enrolment's account reference may arrive in are the programme's decisions rather
than this service's. They are stated in
[`config/membership-programme.json`](config/membership-programme.json), which the
image carries at `/app/config`, and read once at startup by `src/programme.ts`.

`accountReference.acceptedNamespaces` is the statement an enrolment's account
reference is held to; an empty statement accepts the reference as the caller
sends it.

## Local validation

```bash
npm test
```
