# HTTP API Contract

Last updated: 2026-07-05

## Purpose

This directory defines the authoritative HTTP API contract for every bounded
context service in the Train Ticket platform. Every service's HTTP layer must
conform to this specification exactly.

This is the **synchronous-command counterpart** of the event contract in
`docs/08-contracts/events/`. Where a command is exposed via HTTP, the
endpoint definition here is the normative reference. Event-driven-only
commands are marked "bus-only, no HTTP endpoint" and must not have HTTP
endpoints.

## Base Conventions

### Path Pattern

```
/api/v1/<resource>
```

All endpoints use the `/api/v1/` prefix. Path segments are lowercase with
hyphens (kebab-case). Example: `/api/v1/journey-orders`.

### Content Type

All requests and responses use `Content-Type: application/json` (JSON only).
Fields use **camelCase** naming. Enums use **SCREAMING_SNAKE_CASE** (same
serialization rules as `shared-primitives.md`).

### Standard Request Headers

| Header | Required | Description |
|---|---|---|
| `Idempotency-Key` | REQUIRED on every state-changing POST | Client-generated unique key for safe retry. Server must return the original response on replay. Format: UUID v7. |
| `X-Correlation-Id` | RECOMMENDED | End-to-end trace identifier propagated through all services. Generated at the edge if absent. Format: UUID v7. |

### Standard Response Headers

| Header | Description |
|---|---|
| `X-Correlation-Id` | Echoed from request or generated if absent. |
| `X-Request-Id` | Server-assigned unique request identifier for this HTTP call. |

### Canonical Error Body

Every error response returns the following JSON body:

```json
{
  "code": "SCREAMING_SNAKE_ERROR_CODE",
  "message": "Human-readable description",
  "correlationId": "uuid-v7",
  "details": {}
}
```

#### Shared Error Codes

| Code | HTTP Status | Description |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Request body failed structural validation (missing required field, wrong type, enum mismatch). |
| `NOT_FOUND` | 404 | The requested resource or referenced aggregate was not found. |
| `CONFLICT` | 409 | The request conflicts with the current state of the resource (e.g. duplicate creation, version mismatch). |
| `IDEMPOTENCY_KEY_REUSED` | 422 | The `Idempotency-Key` was reused with a different request body. Server must reject with this code. |
| `PRECONDITION_FAILED` | 412 | A precondition specified in the request (e.g. expected version, state check) was not met. |
| `DOMAIN_RULE_VIOLATION` | 422 | The request violates a domain rule (e.g. cannot refund a ticket that has already been used). |
| `UNAVAILABLE` | 503 | The service is temporarily unavailable (e.g. downstream dependency failure, rate limited). |

### Money, Timestamps, IDs, TravelerRef, etc.

All cross-context value objects **MUST** reference
`docs/08-contracts/shared-primitives.md` for their shapes. Do not redefine:

- **Money**: `{ "currency": "CNY", "minorUnits": 12345 }` — ISO-4217 uppercase
  code + integer minor units. Never floats, never decimal strings
  (shared-primitives.md §3).
- **Timestamps**: RFC3339 UTC (e.g. `"2026-07-05T10:30:00Z"`).
- **IDs**: prefixed strings per the shared-primitives.md §2 prefix table.
  The prefix is part of the canonical identity and is REQUIRED, not
  optional (e.g. `"ord-<uuid>"`, `"pi-<uuid>"`).
- **TravelerRef**: string ID `"tvl-<uuid>"`, or the structured form
  `{travelerId, travelerType, maskedDocumentRef?, eligibilityRef?}` where a
  rich reference is needed (shared-primitives.md).
- **Event envelope fields**: `eventId`, `eventType`, `occurredAt`,
  `correlationId`, `causationId`, `producer`, `schemaVersion`, `payload`
  (shared-primitives.md §1).

### Pagination Convention

List/query endpoints returning multiple items support pagination via query
parameters:

| Parameter | Type | Default | Description |
|---|---|---|---|
| `limit` | integer | 20 | Maximum number of items to return (max 100). |
| `offset` | integer | 0 | Number of items to skip. |

Response:

```json
{
  "items": [...],
  "total": 42,
  "limit": 20,
  "offset": 0
}
```

### Health / Readiness Endpoints

All services expose the following endpoints (already defined in platform
profiles):

| Endpoint | Method | Purpose |
|---|---|---|
| `/healthz` | GET | Liveness check. Returns `200 OK` if the process is alive. |
| `/readyz` | GET | Readiness check. Returns `200 OK` if the service can accept traffic (dependencies available). |

These endpoints do not require authentication, idempotency keys, or
correlation IDs.

## Context Files

| # | File | Context | Implemented |
|---|---|---|---|
| 1 | `place-network.md` | Place & Network | go |
| 2 | `service-plan.md` | Service Plan | go |
| 3 | `capacity-availability.md` | Capacity & Availability | rust |
| 4 | `fare-pricing.md` | Fare & Pricing | python |
| 5 | `trip-planning.md` | Trip Planning | python |
| 6 | `offer-management.md` | Offer Management | typescript |
| 7 | `journey-order.md` | Journey Order | java |
| 8 | `booking-orchestration.md` | Booking Orchestration | java |
| 9 | `provider-integration.md` | Provider Integration | go |
| 10 | `payment.md` | Payment | java |
| 11 | `traveler-profile.md` | Traveler Profile | java |
| 12 | `entitlement-ticketing.md` | Entitlement & Ticketing | rust |
| 13 | `notification.md` | Notification | typescript |
| 14 | `risk-compliance.md` | Risk & Compliance | python |
| 15 | `admin-audit.md` | Admin & Audit | java |
| 16 | `supplier-catalog.md` | Supplier Catalog | go |
| 17 | `post-sales.md` | Post Sales | java |
| 18 | `fulfillment.md` | Fulfillment | go |
| 19 | `account.md` | Account | typescript |
| 20 | `customer-service.md` | Customer Service | typescript |
| 21 | `finance-settlement.md` | Finance & Settlement | java |
| 22 | `reporting.md` | Reporting | python |
| 23 | `waitlist.md` | Waitlist | rust (activated, ADR-0002) |
| 24 | `dispatch.md` | Dispatch | activation wave in progress |
| 25 | `wallet-promotion.md` | Wallet / Promotion | activation wave in progress |
| 26 | `disruption-recovery.md` | Disruption Recovery | activation wave in progress |
| 27 | `ancillary-service.md` | Ancillary Service | activation wave in progress |
| 28 | `identity-verification.md` | Identity Verification | activation wave in progress (ADR-0003 wave A) |

## Open Issues

- No open issues at this revision.
