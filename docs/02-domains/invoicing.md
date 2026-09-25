# Invoicing

Last updated: 2026-09-24.

Rust implementation: `services/invoicing`.
The [HTTP API](../08-contracts/api/invoicing.md), [events](../08-contracts/events/invoicing.md) and [service guide](../../services/invoicing/README.md) define current fields and tests.
Remaining work is centralized in [TODO](../TODO.md).

## Ownership

Invoicing owns invoice titles, invoice requests, issued blue and red invoices, red flush observations and itinerary receipt projections.
The invoice gateway is a deterministic SIM adapter.
It does not contact a real tax authority or modify Payment, Journey Order or Post Sales business records.

## Amount basis and issue requests

Order and financial facts are persisted in `invoicing_projection_rows`.
Command entities use `invoicing_command_rows`.
Invoice creation reads the required context through the same PostgreSQL connection as its command transaction.

Amount basis reads require an existing order projection, a recorded expected total, and matching currency and minor units in the financial projection.
Missing or incomplete context returns HTTP 404 for the basis query and HTTP 412 for invoice creation.
These checks establish projection completeness; an order creation event can also supply a total, so total presence alone is not lifecycle confirmation evidence.
After a changed journey, the financial projection can combine original fare revenue, multiple fare-difference recognitions and multiple refund reversals across those recognitions. The projection remains incomplete until its net amount equals the latest Journey Order total.
For `REVENUE_RECOGNITION`, `amountBasisHash` is calculated from sorted recognition IDs, the net total and the complete reversal history keyed by recognition and refund identity. Equivalent business events therefore converge to the same basis across valid arrival orders, new envelope event IDs and process restarts.
Request identity, title version, invoice scope and financial basis are checked before committing invoice and outbox records.
Rejected commands do not commit an accepted command result.

## Refund observations and wallet compensation

The deployed consumer reconciles `PostSalesApplied` and `RefundSettled` against actual settled Payment refunds.
It verifies the case, order, payer and funding references before recording red flush effects.
Red flush observations do not act as an upstream Payment refund gate.
Original invoice and correction evidence remain attributable to their source records.

`PostSalesApplied` with `caseType=COMPENSATION` has no invoice or refund reconciliation effect.
The original amount basis and invoice records remain unchanged when complaint compensation is issued to the wallet.

## Projection concurrency

The Journey Order stream uses four consumers in one group.
Per order PostgreSQL locks and event timestamps preserve the latest applicable total under duplicate and out of order delivery.
Creation facts can complete missing scope references without replacing a subsequent adjustment total.

Recorded tests cover missing context, exact totals, changed-order mixed payments and repeated refunds, stable basis hashes, invoice replay, six event orders, concurrent duplicate delivery, independent order progress and compensation preservation.
Deployment and live verification results are in [current status](../00-current-status.md).
