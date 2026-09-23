# Main event relationships

Last updated: 2026-09-24.

This overview names implemented relationships.
Field level definitions remain in [service event references](../08-contracts/README.md).

| Flow | Command or source | Result | Next owner |
|---|---|---|---|
| Quote | Fare quote and accepted itinerary. | `OfferQuoted` with frozen references. | Journey Order |
| Purchase | Order creation and reservation request. | `JourneyOrderCreated`, `SegmentReservationRequested`. | Booking and Capacity |
| Funds | Payment capture. | `PaymentCaptured`. | Booking, Journey Order and Finance |
| Ticketing | Entitlement issuance. | `EntitlementIssued`. | Journey Order, Fulfillment and Notification |
| Travel | Boarding and completion commands. | `BoardingVerified`, `FulfillmentCompleted`. | Ticketing and other fact consumers |
| Refund | Approved scoped post sales decision. | `PostSalesApproved` with refund and cancellation actions. | Payment and ticketing |
| Capacity release | Original ticket void or reservation cancellation. | `CapacityReleased`. | Post Sales and other release consumers |
| Change | Explicit change executor advances persisted operations. | Replacement ticket and original release facts, then `PostSalesApplied`. | Order and financial projections |
| Complaint compensation | Approved wallet execution. | `BenefitIssued` and `PostSalesApplied` with compensation evidence. | Wallet ledger, benefit costs and Notification |
| Reporting | `PaymentCaptured`. | Gross captured monetary aggregates. | Reporting reads |

`COMPENSATION` approval carries empty ticket action steps and a wallet credit instruction.
Payment ignores that approval for refund creation, and order, finance and invoicing preserve the original travel purchase.
Fare difference and refund results are verified separately from case state.

Event publication can lag behind a successful HTTP response.
A consumer checkpoint or an empty Redis pending list does not establish complete historical replay.
Notification may remain delayed even when the originating business has completed.
All unfinished acceptance is centralized in [TODO](../TODO.md).
