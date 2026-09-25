# Wallet / Promotion

Java service for benefit issuance, wallet balances, reservations, redemption, release, revocation, reversal and expiry.

Last updated: 2026-09-25.

## Redemption policy

Reserve and redeem resolve the referenced Journey Order before changing a
balance. Narrow scopes additionally verify their Post Sales case, service
segments, or fare product code against that order. A request cannot establish
eligibility by supplying matching strings alone.

Reservations are durable records keyed by `(benefitId, reservationRef)` and
retain their original amount, remaining amount, expiry, verified use reference
and business reason. Partial redemption and release update that same record.
An expired reservation returns only its remaining reserved amount to available;
benefit expiry removes all still-available and still-reserved value.

`redemptionRef`, `releaseRef`, and `reversalRef` are business idempotency keys.
An identical replay returns the current persisted result without another
balance, ledger, or event effect. Reuse with different amount, target, scope, or
reason is a conflict. During the benefit validity window, reversal moves value
from redeemed back to available. After benefit expiry or revocation, reversal
only corrects redeemed history and does not restore spendable value.

For every benefit, `available + reserved + redeemed <= issued`. The matching
wallet sub-ledger receives the same deltas in the same transaction.

## Complaint compensation issuance

`POST /api/v1/benefits` accepts the request recorded by Post Sales for an approved `COMPENSATION` case.
The recipient is the original order account, and the issued amount equals the approved `compensationAmount` in both currency and minor units.
The request carries `benefitType=COMPENSATION_CREDIT`, `balanceType=PROMOTION_CREDIT`, `issuanceSource=POST_SALES_COMP` and the canonical `psc-` case reference.
It credits the matching available wallet balance and emits `BenefitIssued` with the case reference and ledger delta.
The original tickets, seat reservations, order and captured payment remain available for travel.

Post Sales currently records an `ANY_TRIP` scope, `singleUse=false`, `requiresReservation=false`, an empty `revocationRule` request object and a validity window of 30 days from request creation.
The original `validFrom` and `validUntil` values are retained on retry.
This duration is supplied by the compensation executor; the wallet issuance API accepts the explicit validity window in each request.
The business reason uses `reasonType=POST_SALES_COMP`, the case `reasonCode`, `referenceType=POST_SALES_CASE` and `referenceId=caseId`.
Zero approved compensation amounts complete in Post Sales without calling wallet issuance.

## Issuance deduplication and concurrency

Every issuance has a durable business identity. `POST_SALES_COMP` and
`DISRUPTION_COMP` use their canonical `caseId`; a caller may provide
`issuanceRef` explicitly; legacy manual issuance derives the key from account
and the complete business reason. An identical command returns the existing
benefit even with another HTTP idempotency key. Reusing an issuance identity
with different account, amount, types, rules, validity timestamps or reason
returns HTTP 409 `CONFLICT`. A replay adds no benefit, wallet credit, ledger
entry or `BenefitIssued` event.
An existing compensation benefit without its recorded issuance request returns HTTP 409 `CONFLICT` and remains unchanged.
The database also enforces one compensation benefit per case through a unique partial index.
The HTTP filter separately returns `IDEMPOTENCY_KEY_REUSED` when a stored idempotency key is reused for a different request.

The PostgreSQL repository acquires a transaction advisory lock for the receiving
account before reading or writing any wallet mutation. It then rereads the
current benefit, wallet and operation record inside the lock. Different
benefits owned by one account therefore cannot race on the shared wallet
snapshot, and equal issuance/reservation/redemption/release/reversal replays
converge instead of surfacing a snapshot version conflict.

Migration `007_durable_issuance_identity.sql` upgrades the compensation table to
`benefit_issuances`, keys it by the durable issuance identity and backfills
existing benefit snapshots. The table retains a unique `benefit_id` and the
original request as JSONB.
The issuance record, benefit snapshot, wallet snapshot, ledger entry and event outbox are written in the issuance transaction.
The in-memory repository applies the same business-key comparison.

## Execution evidence

Post Sales records `compensationOperations.request`, `issued` and `verified` and reads `GET /api/v1/benefits/{benefitId}` to verify positive compensation amounts before completing the case.
The executor requires an approved plan containing `ISSUE_COMPENSATION_CREDIT` and an original payment intent in `CAPTURED` with zero `refundedAmount`.
Its `verified` evidence includes the original `paymentIntentId` and `capturedAmount`.
Completion records `ISSUE_COMPENSATION_CREDIT` and `APPLY_RESULT` as `SUCCEEDED` in order before applying the case.
Public case status remains `APPROVED` during execution and becomes `APPLIED` after verification.
Wallet issuance is driven by the HTTP request carrying `caseId`.
See the [HTTP API](../../docs/08-contracts/api/wallet-promotion.md), [events](../../docs/08-contracts/events/wallet-promotion.md) and [Post Sales execution API](../../docs/08-contracts/api/post-sales.md).

## Owns

`PromotionInstrument`, `WalletAccount`, `WalletBalance`, `BenefitReservation`,
`BenefitRedemption`, release/reversal records and wallet ledger entries.

## DDD Sources

`docs/02-domains/wallet-promotion.md`.

## Language Rationale

Java represents benefit commands, monetary values and transaction boundaries explicitly.

## Check

```bash
mvn test
```

Set `TEST_DATABASE_URL` to execute `WalletMutationConcurrencyLiveTest`. It runs
16-way concurrent same-operation replay, distinct issuance to one account,
mixed redeem/release, reservation expiry and reversal, asserting snapshots,
ledger rows, outbox rows and business-operation records after every phase.

`CompensationIssuanceLiveTest` uses a recorded approved case and its actual order account with an isolated PostgreSQL schema.
It verifies eight concurrent requests, service reconstruction, amount conflict and refusal when a benefit exists without its issuance record.
The verified run completed both live tests in 4.792 seconds, with one benefit, ledger entry and `BenefitIssued` retained per case.

Loadgen's `TestLiveWalletCompensationPreservesPaidTravel` contains assertions for an equal wallet balance increment, three case and wallet issuance replays with stable identities, preserved tickets, seats, Holds, payments and ancillary item, and completed fulfillment.
The focused test passes against revision 274 in 17.031 seconds with an approved CNY 8.86 credit and successful subsequent fulfillment.
