# travel-insurance

Domain: Travel Insurance

Language: go

Status: implemented

## Owns

- `Policy` aggregate root: issued against a journey order's ancillary item, with a coverage window, a premium in `Money`, and a status lifecycle.
- `Claim` aggregate root: filed against a policy, then approved or rejected. `DELAY_AUTO` claims are the automatic path, filed from a disruption rather than by a traveller.
- `InsuranceProduct`: the versioned catalogue a policy is issued from. A policy names both the code and the version, so a product that changes later does not change what was sold.

## Catalogue

The four products and the version they are published under live in
[`config/insurance-products.properties`](config/insurance-products.properties),
beside the loader that reads them; the autumn release carries `2026.10`. A
premium or a coverage limit is a settings change and not a rebuild, and a policy
keeps the product version it was issued from after the catalogue moves on.

The sales window is not in the file: a product is published from a day
before startup to two years after it, so a retried purchase of a policy sold
yesterday still finds its product.

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `travel_insurance` on the `core` PostgreSQL instance
  (`services.travel-insurance.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/ready`,
`/metadata`.

The full contract lives in
[`docs/08-contracts/api/travel-insurance.md`](../../docs/08-contracts/api/travel-insurance.md).

## Events

Publishes `InsurancePolicyIssued`, `InsuranceClaimCreated`,
`InsuranceClaimApproved`, `InsuranceClaimRejected` and
`InsurancePayoutCompleted` to `events:travel-insurance`; see
[`docs/08-contracts/events/travel-insurance.md`](../../docs/08-contracts/events/travel-insurance.md).
Consumes `events:ancillary-service`, `events:post-sales`,
`events:service-plan` and `events:journey-order`
(`cmd/travel-insurance/main.go`).

The service consumes `TrainDelayed` and files a `DELAY_AUTO` claim from it
when the delay is over 60 minutes; a shorter delay leaves the policy alone,
because the coverage the traveller bought is the delay cover the product
publishes.

A read that an announcement promises is treated as a wait rather than a rule
violation: `settleNotReady` retries the owning-service read five times, 400 ms
apart, inside the delivery and only then declares a transient failure
(`adapters/messaging/subscriber.go`, `application/purchase_verification.go`).

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

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

A claim's status is what a reader acts on: `PENDING` waits for a decision,
`APPROVED` means the payout advice exists but no Wallet benefit has been read
back, `PAID_OUT` means one has, and `REJECTED` closes the claim with a reason
code.

The downstream URL template defaults to `http://%s:8080` and can be overridden with `INSURANCE_PAYOUT_URL_TEMPLATE`.

The advice moves from `REQUESTED` to `COMPLETED` in the same transaction that
marks the claim `PAID_OUT`; a read-back that does not match leaves it
`REQUESTED`, which is the state a retry looks for.

The service also consumes `JourneyOrderPostSalesAdjusted`. It resolves the applied change and current order through their owning APIs, then updates each affected issued policy to the replacement segment and travel window. It never guesses dates from event time, and it does not rewrite terminal policies.

## Tests

```bash
go test ./...
```
