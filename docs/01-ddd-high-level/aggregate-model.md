# Aggregate ownership

Last updated: 2026-09-24.

| Business object | Owner | Protected fact |
|---|---|---|
| Itinerary | Trip Planning | Candidate legs and source references. |
| Offer | Offer Management | Quote, disclosure and validity snapshot. |
| JourneyOrder | Journey Order | Accepted commercial order and its item summaries. |
| SegmentBooking | Booking Orchestration | One traveler reservation and its progress. |
| CapacityHold | Capacity & Availability | Admission reference, interval and capacity lifecycle. |
| ContractSeatMap and ContractSeatAllocation | Seat Assignment | Composition and actual seat, berth or standing assignment. |
| PaymentIntent and Refund | Payment | Captured/refunded monetary values and callback identity. |
| Entitlement | Entitlement & Ticketing | Issued and usable ticket rights. |
| FulfillmentRecord | Fulfillment | Boarding, no show and completed travel facts. |
| PostSalesCase | Post Sales | Scope, approved decision and recorded downstream execution. |
| PromotionInstrument and WalletAccount | Wallet / Promotion | Noncash benefit value, ledger and case issuance identity. |
| Invoice title/request/invoice | Invoicing | Issuance basis, invoice identity and red flush state. |
| NotificationTask | Notification | Trigger, recipient and delivery receipts. |

Aggregates refer to other owners by stable business identities.
Their state and outbox effects commit in local transactions.
Read projections may combine source facts, but they do not authorize direct writes to another owner's state.
Payment success, ticket issuance, order confirmation and delivery acceptance remain distinct facts.

Service quality `COMPENSATION` records an approved wallet credit with unchanged original travel rights.
`Refund` continues to refer to an actual payment and does not represent that wallet credit.
A recorded compensation result includes the issued benefit and verified original payment reference.

Capacity Hold pool references and physical SeatUnit identities are separate in the deployed path.
The worktree supply integration is explicitly described in [Capacity design](../10-domain-enrichment/capacity-overbooking.md).
The notification durable sending queue is also unpublished and is described in [Notification design](../02-domains/notification.md).

Exact object fields, monetary serialization and states come from the owning [API](../08-contracts/api/README.md) and event files.
Remaining work is in [TODO](../TODO.md).
