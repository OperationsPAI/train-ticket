# Legacy ACL — HTTP API

Last updated: 2026-07-07

## Overview

The Legacy ACL is the strangler facade for the old Train Ticket entrypoints
(WP-23, DR-014). It maps legacy-shaped operations onto the new bounded
contexts' commands — it holds NO state of its own and NEVER writes any
domain's state directly. Every mapped operation publishes a
`LegacyCommandMapped` fact carrying operator/reason/audit references
(events/legacy-acl.md); Admin & Audit consumes it into the audit trail.

All endpoints return the legacy response shape:

```json
{ "status": 1, "msg": "...", "data": { ... } }
```

`status` 1 = success, 0 = failure. `data` carries the new-world references
(orderId, paymentIntentId, entitlementId, caseId, …) so callers can migrate
incrementally.

All endpoints require headers `X-Legacy-Operator` (operator ref) and accept
optional `X-Legacy-Reason`. Idempotency-Key REQUIRED on all POSTs.

## Endpoints

### Preserve (legacy 下单)

**POST** `/api/v1/legacy/preserve`

**Request:** `{ "accountId", "contactsId" (traveler ref or inline
{name, documentType, documentNumber}), "tripId" (legacy train number, e.g.
G1234), "seatType", "date", "from", "to" }`

Maps to: trip search → fare quote → offer → CreateJourneyOrder →
request-reservation. Returns `data.orderId`, `data.offerId`, `data.total`.

### Inside Payment (legacy 支付)

**POST** `/api/v1/legacy/inside_payment`

**Request:** `{ "orderId", "price": {"currency","minorUnits"} }`

Maps to: create payment intent (businessRef=orderId) → capture. Returns
`data.paymentIntentId`.

### Ticket Issue (legacy 出票 — part of legacy pay flow)

**POST** `/api/v1/legacy/ticket_issue`

**Request:** `{ "orderId" }`

Maps to: issue entitlement for the order's segment booking. Returns
`data.entitlementId`.

### Execute (legacy 检票/进站)

**POST** `/api/v1/legacy/execute`

**Request:** `{ "orderId" }`

Maps to: fulfillment VerifyBoarding for the order's entitlement. Returns
`data.fulfillmentRecordId`.

### Cancel (legacy 退票)

**POST** `/api/v1/legacy/cancel`

**Request:** `{ "orderId" }`

Maps to: post-sales REFUND case open → evaluate → approve. Returns
`data.caseId`, `data.refundAmount`.

### Rebook (legacy 改签)

**POST** `/api/v1/legacy/rebook`

**Request:** `{ "orderId", "date", "seatType" }`

Maps to: post-sales CHANGE case open → evaluate → approve (old ticket
teardown); rebooking the replacement journey is the caller's follow-up
`preserve` (phase-1 scope). Returns `data.caseId`, `data.amountDue`.

## Rules

- The ACL resolves order context (segment bookings, entitlements, traveler
  refs) ONLY through the owning contexts' query APIs — no shared storage.
- Downstream failures surface as `status: 0` with the downstream error
  message in `msg`; the ACL never retries destructive operations itself.
- Every operation publishes `LegacyCommandMapped` BEFORE returning success.

## Open Issues

- None.
