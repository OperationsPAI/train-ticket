# Payment

Last updated: 2026-09-24.

Java implementation: `services/payment`.
[HTTP API](../08-contracts/api/payment.md), [events](../08-contracts/events/payment.md) and [service guide](../../services/payment/README.md) define current operations.
Remaining acceptance is centralized in [TODO](../TODO.md).

## Ownership

Payment owns `PaymentIntent`, `Refund`, callback identity, captured and refunded balances, and late payment cases.
Payment Channel is a separate Go service owning simulated channel orders, callbacks, refunds and statements.
Payment consumes normalized channel results and publishes financial facts.
It does not confirm commercial orders, allocate seats, issue tickets or calculate refund eligibility.

## State and money

`PaymentIntentStatus` is `CREATED, AUTHORIZED, CAPTURED, FAILED, CANCELLED, EXPIRED`.
`RefundStatus` is `REQUESTED, SUBMITTED, SETTLED, FAILED, MANUAL_REVIEW_REQUIRED`.
Internal decimal money is mapped to currency and integer minor units at the ticketing API boundary.
Capture and refund identities retain the actual channel references.
Refund currency and value are checked against the captured payment and remaining balance.

A capture after cancellation or expiry belongs to a recorded late payment workflow.
It does not recreate an order or ticket.
A settled refund is a funds fact; Post Sales case state and original ticket rights must be checked independently.

## Post Sales and ancillary integration

Approved refunds identify their business case and funding payment.
Repeated changes may use multiple referenced payments; they are not inferred from the current order total alone.
The change executor owns fare difference collection or refunds through the Payment API.
`PostSalesApproved` for `COMPENSATION` is ignored for payment refund creation.
That case issues Wallet / Promotion credit while the original capture and refundable balance remain unchanged.
Ancillary refund intents retain their own payment and item references.

## Persistence and verification

PostgreSQL repositories commit financial snapshots, business identity and outbox events together.
HTTP idempotency records and callback identity prevent repeated monetary effects.
Recorded PostgreSQL compensation tests verify unchanged payment rows, zero new refunds and zero payment outbox events after duplicate event delivery and service reconstruction.
Actual channel settlement, late results and complete historical replay require the evidence identified in TODO.
