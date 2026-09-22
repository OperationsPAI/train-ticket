# Post sales conflict reconciliation

`POST /api/v1/internal/post-sales-cases/{caseId}/reconcile-conflict` requires `Idempotency-Key`.
The request identifies only the conflicting case.
The service resolves the original refund, payment results and ticket status itself.

The operation requires an executing change, no completed execution steps, no released bookings, no refund amount and no payment or refund records for the change.
Exactly one applied refund or cancellation must cover all its tickets, bookings and travelers.
The original refund must have settled for its approved amount, and every affected ticket must have been voided before the change was created.

The conflicting change becomes `FAILED` with `terminalReason=SCOPE_ALREADY_REFUNDED:{originalCaseId}`.
The case, failure reason and `PostSalesFailed` event are committed together.
Repeated calls return the same result without publishing duplicate events.
This operation never issues tickets, changes payments or deletes cases.
