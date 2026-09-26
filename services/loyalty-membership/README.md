# Loyalty Membership

TypeScript service for membership tier state, points ledger entries and lots,
rolling tier evaluation, expiry and points redemption through Wallet / Promotion.

## Local validation

```bash
npm test
```

## Membership programme

The membership programme is member relations' export, and this release is built
against one revision of it: the revision the settings were taken from, the names the
export publishes and the namespaces an enrolment's account reference may arrive in are
the programme's decisions. They are stated in
[`config/membership-programme.json`](config/membership-programme.json), which the
image carries at `/app/config` and `src/programme.ts` reads once.

`accountReference.acceptedNamespaces` is the statement an enrolment's account
reference is held to. It ships empty, which accepts a reference as the caller sends
it, and a deployment that states the namespaces its references arrive in lists them
there.
