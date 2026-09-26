# Loyalty Membership

TypeScript service for membership tier state, points ledger entries and lots,
rolling tier evaluation, expiry and points redemption through Wallet / Promotion.

## Local validation

```bash
npm test
```

## Membership programme

The programme member relations exports moves on its own cadence, and this service
is released against one revision of it. The namespaces an enrolment's account
reference may arrive in are the programme's decision, so they are stated in
[`config/membership-programme.json`](config/membership-programme.json), which the
image carries at `/app/config` and `src/programme.ts` reads once.

`accountReference.acceptedNamespaces` is the statement an enrolment's account
reference is held to. The statement ships empty, which accepts the reference as
the caller sends it, and a deployment that states the namespaces its references
arrive in lists them there.
