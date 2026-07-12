# provider-integration

Domain: Provider Integration

Language: golang

Phase: phase-1-acl-foundation

Status: REQ-015 tested domain foundation

## Owns

- provider request and callback identity
- idempotency scope for external provider intents
- raw archive references for request, response, webhook, status, and error payloads
- normalized provider error classification
- mapping confidence metadata
- external status mapping into internal facts
- explicit quarantine for unmapped raw provider statuses
- retry and manual-review directives for ambiguous external outcomes

## Does Not Own

- SegmentBooking, JourneyOrder, PaymentIntent, Entitlement, Capacity, or PostSales aggregate state
- user-facing provider error copy
- real provider network calls in this foundation slice

## REQ-015 ACL Facts

The first ACL delivery maps reviewed provider/channel statuses into stable internal facts:

- `PROVIDER_RESERVATION_CONFIRMED`
- `PROVIDER_RESERVATION_FAILED`
- `CHANNEL_PAYMENT_CAPTURED`
- `CHANNEL_REFUND_SETTLED`
- `SUPPLIER_TICKET_ISSUED`
- `PROVIDER_BOARDING_ACCEPTED`

Unmapped raw provider status codes are rejected into quarantine with raw archive pointers and manual-review action instead of leaking into core domains. Ambiguous states produce query-status or manual-review directives rather than blind replay of side-effecting operations.

## DDD Sources

- `docs/02-domains/provider-integration.md`
- `docs/01-ddd-high-level/acl-provider-contracts.md`

## Language Rationale

Go fits protocol adapters, webhooks, retry loops, and small deployable edge services.

## Checks

```bash
go test ./...
```
