# travel-insurance

Domain: Travel Insurance

Language: go

Status: implemented

## Owns

- `Policy` aggregate root: issued against a journey order's ancillary item, with a coverage window, a premium in `Money`, and a status lifecycle.
- `Claim` aggregate root: filed against a policy, then approved or rejected. `DELAY_AUTO` claims are the automatic path, filed from a disruption rather than by a traveller.
- `InsuranceProduct`: the versioned catalogue a policy is issued from. A policy names both the code and the version, so a product that changes later does not change what was sold.

## API

| Method | Path |
|---|---|
| POST | `/api/v1/policies` |
| GET | `/api/v1/policies/{id}` |
| POST | `/api/v1/claims` |
| GET | `/api/v1/claims/{id}` |
| POST | `/api/v1/claims/{id}/approve` |
| POST | `/api/v1/claims/{id}/reject` |

Policy issue is idempotent on the request's idempotency key, because it is called from the purchase funnel where a retry must not sell a second policy.

## Claim payout

An approved claim is paid as Wallet stored value through Wallet / Promotion's owning `POST /api/v1/benefits` API. Travel Insurance first persists a `REQUESTED` payout advice with a stable UUID-v7 idempotency key, then issues a `MANUAL_OPS` `BALANCE` benefit with `issuanceRef=insurance-claim:{claimId}:v1` and an `INSURANCE_CLAIM` business reference. It reads the resulting benefit back from Wallet and verifies the account, currency, amount and claim reference before marking the advice, claim and policy complete.

`PAID_OUT` and `InsurancePayoutCompleted` therefore mean that a concrete Wallet benefit was observed. A timeout or invalid Wallet response leaves the claim `APPROVED` and the payout advice `REQUESTED`; retrying reuses the same advice and idempotency key. `PAYMENT_REFUND` is not accepted for claim proceeds because a claim is not a reversal of the original premium or ticket payment.

The downstream URL template defaults to `http://%s:8080` and can be overridden with `INSURANCE_PAYOUT_URL_TEMPLATE`.

The service also consumes `JourneyOrderPostSalesAdjusted`. It resolves the applied change and current order through their owning APIs, then updates each affected issued policy to the replacement segment and travel window. It never guesses dates from event time, and it does not rewrite terminal policies.

## Tests

```bash
go test ./...
```
