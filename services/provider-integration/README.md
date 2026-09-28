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

## Runtime

- HTTP port `8080` for probes and metadata only; there are no HTTP command
  endpoints (`docs/08-contracts/api/provider-integration.md`).
- Database `provider_integration` on the `core` PostgreSQL instance
  (`services.provider-integration.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/ready`,
`/metadata`.

The full contract lives in
[`docs/08-contracts/api/provider-integration.md`](../../docs/08-contracts/api/provider-integration.md).

## Events

Publishes `ProviderReservationConfirmed`, `ProviderReservationFailed`,
`ProviderReservationTimedOut`, `ProviderTicketIssued`, `SegmentDelayed` and
`SegmentCancelled` to `events:provider-integration`; see
[`docs/08-contracts/events/provider-integration.md`](../../docs/08-contracts/events/provider-integration.md).
Consumes `events:booking-orchestration` and `events:supplier-catalog`
(`internal/adapters/messaging/subscriber.go`).

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Language Rationale

Go fits protocol adapters, webhooks, retry loops, and small deployable edge services.

## Checks

```bash
go test ./...
```
