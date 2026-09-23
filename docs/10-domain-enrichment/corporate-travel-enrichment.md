# Corporate Travel implementation

## Implementation

Corporate Travel is a Python service with agreements, authorized travelers, travel policies, approval records, budget pools and billing periods.
Policy checks cover seat class, advance booking, approved routes and budget limits.
Compliant requests create an approved record and reserve employee, department and agreement budgets.
Requests needing approval record the required level; finance approval requests do not reserve additional budget, while other exceptions can reserve above the limit.
Applicable corporate payment events commit reservations, and the handled refund or cancellation events release them.
Monthly invoice generation calculates totals and discounts from submitted line items and publishes `ConsolidatedInvoiceGenerated`.

## Interfaces and sources

[HTTP routes](../../services/corporate-travel/src/corporate_travel/api.py) expose agreement creation and lookup, traveler authorization, `policy-checks` and `monthly-invoices` under `/api/v1/agreements`, with equivalent unprefixed routes.
[Domain](../../services/corporate-travel/src/corporate_travel/domain.py), [application service and persistence](../../services/corporate-travel/src/corporate_travel/application.py) and [event handler](../../services/corporate-travel/src/corporate_travel/messaging.py) define policy, approval, budget and billing behavior.
[Service README](../../services/corporate-travel/README.md) describes agreement and billing ownership.

## Boundaries

The approval model supports decisions and escalation, while the HTTP routes expose policy checks and creation of approval records.
Approval decisions have no HTTP operation, and the runtime contains no simulated approver scheduler.
Budget and billing event processing accepts `JourneyOrderConfirmed`, `PaymentCaptured`, `PostSalesRefundCompleted` and `TripCancelled`; corporate billing requires an `agreementId`.
The monthly invoice endpoint uses caller supplied lines and discount rate; it does not automatically discover all completed trips or issue a tax invoice.
Deployment and verification evidence, including differences from workspace code, are recorded in [current status](../00-current-status.md) for Helm revision 279.
Remaining work is maintained only in [TODO](../TODO.md).
