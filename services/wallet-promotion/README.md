# Wallet / Promotion

Java service for benefit issuance, wallet balances, reservations, redemption, release, revocation, reversal and expiry.

Last updated: 2026-09-23.

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

## Case deduplication

For `issuanceSource=POST_SALES_COMP`, the service checks the complete `IssueBenefitCommand` against any issuance recorded for the same `caseId`.
An identical command returns the existing benefit, including when the caller supplies a different HTTP idempotency key.
A command differing in account, amount, types, rules, validity timestamps or business reason returns HTTP 409 `CONFLICT`.
An accepted replay adds no benefit, wallet credit, ledger entry or `BenefitIssued` event.
An existing compensation benefit without its recorded issuance request returns HTTP 409 `CONFLICT` and remains unchanged.
The database also enforces one compensation benefit per case through a unique partial index.
The HTTP filter separately returns `IDEMPOTENCY_KEY_REUSED` when a stored idempotency key is reused for a different request.

The PostgreSQL repository acquires transaction advisory locks for the case and receiving account before checking or creating the issuance.
Migration `005_compensation_issuance.sql` defines `compensation_issuances` with `case_id` as its primary key, a unique `benefit_id` and the original request as JSONB.
The issuance record, benefit snapshot, wallet snapshot, ledger entry and event outbox are written in the issuance transaction.
The in memory repository also compares recorded commands by case.

## Execution evidence

Post Sales records `compensationOperations.request`, `issued` and `verified` and reads `GET /api/v1/benefits/{benefitId}` to verify positive compensation amounts before completing the case.
The executor requires an approved plan containing `ISSUE_COMPENSATION_CREDIT` and an original payment intent in `CAPTURED` with zero `refundedAmount`.
Its `verified` evidence includes the original `paymentIntentId` and `capturedAmount`.
Completion records `ISSUE_COMPENSATION_CREDIT` and `APPLY_RESULT` as `SUCCEEDED` in order before applying the case.
Public case status remains `APPROVED` during execution and becomes `APPLIED` after verification.
Wallet issuance is driven by the HTTP request carrying `caseId`.
See the [HTTP API](../../docs/08-contracts/api/wallet-promotion.md), [events](../../docs/08-contracts/events/wallet-promotion.md) and [Post Sales execution API](../../docs/08-contracts/api/post-sales.md).

## Owns

`PromotionInstrument`, `WalletAccount`, `WalletBalance`, `BenefitRedemption` and wallet ledger entries.

## DDD Sources

`docs/02-domains/wallet-promotion.md`.

## Language Rationale

Java represents benefit commands, monetary values and transaction boundaries explicitly.

## Check

```bash
mvn test
```

`CompensationIssuanceLiveTest` uses a recorded approved case and its actual order account with an isolated PostgreSQL schema.
It verifies eight concurrent requests, service reconstruction, amount conflict and refusal when a benefit exists without its issuance record.
The verified run completed both live tests in 4.792 seconds, with one benefit, ledger entry and `BenefitIssued` retained per case.

Loadgen's `TestLiveWalletCompensationPreservesPaidTravel` contains assertions for an equal wallet balance increment, three case and wallet issuance replays with stable identities, preserved tickets, seats, Holds, payments and ancillary item, and completed fulfillment.
The focused test passes against revision 274 in 17.031 seconds with an approved CNY 8.86 credit and successful subsequent fulfillment.
