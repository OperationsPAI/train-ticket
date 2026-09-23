# Order, capacity, payment and ticket model

Last updated: 2026-09-24.

The commercial order, reservation, funds, capacity and ticket are separate lifecycles.
Clients retain the identities returned by each owning service.

## Purchase

1. Trip Planning returns a stored itinerary backed by Service Plan references.
2. Fare Pricing produces the quotation and accepted eligibility inputs; Offer Management freezes the accepted quote.
3. Journey Order validates the accepted source and identity check before creating its commercial record.
4. Booking Orchestration creates traveler reservations and coordinates capacity and payment facts.
5. Entitlement Ticketing obtains and records the seat allocation when issuing the ticket.
6. Journey Order confirmation reflects required reservation, funds and ticket conditions.

The executed request sequence is visible in `deploy/loadgen-go/journey_purchase.go`.
Actual API and event definitions are in [the interface reference](../08-contracts/README.md).

## Refund, change and compensation

| Operation | Travel rights | Funds |
|---|---|---|
| REFUND or CANCELLATION | Approved actions void scoped tickets and release scoped reservations. | Payment executes the approved refund; settlement is verified independently. |
| CHANGE | Replacement reservations and tickets are recorded before original ticket void and release. | Approved fare difference is collected or returned using referenced payments. |
| COMPENSATION | Original tickets, seats, order and payment remain available. | Wallet receives the approved compensation amount, deduplicated by case. |

Post Sales `APPLIED` alone is not proof of a settled payment refund.
Loadgen checks Payment settlement and actual ticket/capacity results.
Failed replacement capacity, uncaptured difference payment or expired target retain original rights through their recorded compensation paths.
An original ticket already voided is not restored by retrying a payment operation.

## Capacity source boundary

Seat Assignment owns published physical composition and the ten percent standing quota.
Standing allocation has policy evidence and no physical SeatUnit.
Service Plan currently creates two stop services and exposes interval `[0,1)` with a stop sequence digest.
Capacity combined supply calculation and the matching Booking source path are not jointly deployed.
See [current supply design](../10-domain-enrichment/capacity-overbooking.md).

## Replay

Commands retain HTTP idempotency and business identity.
Recorded downstream steps allow a retry to inspect its existing effects.
A timed out call does not justify a second capture, refund, ticket or wallet issuance.
Terminal compensation mismatches remain auditable and unavailable for automatic reexecution.
Unfinished verification and integration are centralized in [TODO](../TODO.md).
