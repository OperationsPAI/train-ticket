# Waitlist implementation

Last updated: 2026-09-24.

The domain boundary is documented in [Waitlist](../02-domains/waitlist.md).
Remaining work is tracked in [docs/TODO.md](../TODO.md).

## Runtime and storage

The TypeScript service stores requests in PostgreSQL and publishes events through a transactional outbox.
The public request interface is [Waitlist HTTP API](../08-contracts/api/waitlist.md).
Requests have `wlr-` identifiers and the statuses `DRAFT`, `QUEUED`, `MATCHING`, `FULFILLED`, `EXPIRED`, `CANCELLED`, `SUSPENDED` and `CLOSED`.
The queue sorts priority descending, creation time ascending and entry identifier ascending.
The database enforces active request uniqueness for the leading traveler and intent fingerprint.
Current queue queries filter by segment and optional class and do not enforce the supplied departure date.
The application derives the date from the deadline when it is absent from the request.

## Matching and confirmation

The application selects a queued request, obtains a current fare quote and commercial Offer, and starts an actual Journey Order.
The active promotion method does not directly call the Capacity Hold client.
Persisted UUID v7 keys identify each downstream operation.
Creating the order records its reference and leaves the request `MATCHING`.
`JourneyOrderConfirmed` changes it to `FULFILLED` and publishes `WaitlistFulfilled`.
Notification consumes this success event.

`JourneyOrderCancelled` clears the matching order and offer and renews the next attempt's quote, Offer and order keys.
The request returns to `QUEUED` if its deadline permits another attempt, otherwise it becomes `EXPIRED`.
Expiry scans do not expire requests with a linked order still being processed.
Archival changes fulfilled, expired or cancelled requests to `CLOSED` and preserves their recorded references.
Repeated confirmation after fulfillment or archival produces no new success event.

## Priority calculation

The total is clamped to the range 0 through 100.

| Factor | Current calculation |
|---|---|
| Loyalty | Platinum 30, gold 20, silver 10, none 0 |
| Booking history | More than 20 trips 20; 10 through 20 trips 15; 5 through 9 trips 10; fewer than 5 trips 5 |
| Advance booking | More than 14 days 15; 7 through 14 days 10; fewer than 7 days 5 |
| Group size | One traveler 10; two travelers 8; three or four travelers 5; larger group 2 |
| Fare class | Business 15, first 12, second 8, standing 3 |
| Special status | Military or disabled 10; student 5; none 0 |

Priority inputs come from the request and do not establish independently verified loyalty or special status.
The queue, fare quote and released inventory do not currently enforce one canonical class throughout the flow.
The fare quote client does not send the entry's seat class.
The current implementation does not enforce multi traveler mutual exclusion independently for every companion.

## Existing verification scope

`tests/confirmation-live.test.ts` uses actual recorded order facts with PostgreSQL to verify pending matching, confirmation, archived replay, cancellation and renewed keys.
It also verifies that a row lock timeout is retryable and leaves no partial success event.
`tests/order-index-live.test.ts` verifies indexed order lookup using recorded waitlist rows.
`tests/current-clients-live.test.ts` exercises the current downstream response fields against deployed services.
`deploy/loadgen-go/live_waitlist_confirmation_test.go` verifies payment and ticket issuance before candidate fulfillment.
These test sources describe their specific confirmation and client scenarios, not a complete automatic fulfillment acceptance result.
No tests were added or rerun for this documentation update.

## Current integration boundary

The `WaitlistCapacityFreed` parser requires `segmentRef`, `departureDate` and a positive released quantity.
The ordinary `CapacityReleased` handler only promotes when the matching fields are present; it acknowledges and skips releases without a date.
The deployed ordinary release event lacks that date, so automatic promotion from those releases is not established.
The public request and response use the leading traveler; internal traveler arrays do not establish complete preservation of all companions through the public flow.
The required `paymentGuaranteeRef` is recorded, but it does not currently establish or settle a payment authorization.
Capacity's combined supply calculation is uncommitted and undeployed, and Booking coordinated behavior is not enabled.
Successful request creation and the recorded order confirmation regression do not establish complete automatic waitlist fulfillment.
