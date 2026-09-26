# Entitlement and Ticketing

Last updated: 2026-09-24.

Rust implementation: `services/entitlement-ticketing`.
The [HTTP API](../08-contracts/api/entitlement-ticketing.md), [events](../08-contracts/events/entitlement-ticketing.md) and [service guide](../../services/entitlement-ticketing/README.md) describe concrete states and operations.
Remaining acceptance is in [TODO](../TODO.md).

## Ownership

An Entitlement is an issued travel right identified independently from order, payment and reservation state.
Ticketing owns its credential uniqueness, lifecycle and audit evidence.
Booking owns reservation progress, Payment owns funds, and Seat Assignment owns actual physical or standing allocation.
Fulfillment supplies boarding and completion facts.

## Issuance

Train issuance reads accepted booking and financial references and obtains a Seat Assignment allocation before committing a ticket.
The ticket records the returned seat, berth or standing `seatRef`.
Standing has no physical SeatUnit and includes the versioned quota evidence defined by the [standing policy](../08-contracts/standing-capacity-policy.md).

Issuance identity includes the traveler reservation and issue purpose.
Replay must retain the issued ticket and allocation instead of selecting another composition or physical unit.
Valid Seat Assignment business rejections preserve their status, code and message.
Transport failures and invalid downstream responses remain service errors.

## Lifecycle collaboration

| Source | Effect |
|---|---|
| Authorized issue command | Issue a ticket after required source checks and allocation. |
| Post Sales refund actions | Void scoped entitlements, allowing owning reservation/capacity consumers to release their effects. |
| Change executor | Issue authorized replacement tickets and separately void original tickets after recorded replacement progress. |
| Wallet compensation approval | Empty ticket actions preserve all original entitlements. |
| Fulfillment facts | Record boarding and completion against the referenced entitlement. |

Used or voided ticket states are not reconstructed by retrying a refund or changing an order summary.
Domain operations enforce applicable terminal state and exceptional void policies.
PostgreSQL stores ticket snapshots and outbox effects; consumers retain their business deduplication evidence.
Actual service state and focused business tests, not an abstract state diagram, establish the available ticket operations.

## Boarding roster

The readers whose evidence a boarding fact may carry are the estate's own statement, so the ticketing authority publishes the estate's boarding roster in
`services/entitlement-ticketing/config/boarding-readers.txt`.
Fulfillment vendors the same file into its image at build and measures a boarding fact against it, which is what keeps the two sides from drifting.
A deployment that ships no roster keeps the vocabulary its image was built with.
