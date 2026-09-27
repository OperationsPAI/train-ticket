# Loyalty Membership

TypeScript service for membership tier state, points ledger entries and lots,
rolling tier evaluation, expiry and points redemption through Wallet / Promotion.

## Local validation

```bash
npm test
```

## Membership programme

The membership programme this release is built against is member relations'
export. The revision the settings were taken from and the statement an enrolment's
account reference is held to are stated in
[`config/membership-programme.json`](config/membership-programme.json), which the
image carries at `/app/config` and `src/programme.ts` reads once.

The namespaces the programme states are the statement an enrolment's account
reference is held to: the account contract this platform publishes puts its account
references in the `acct` namespace, so the programme states that namespace.
