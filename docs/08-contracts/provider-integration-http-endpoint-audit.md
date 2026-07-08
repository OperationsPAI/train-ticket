# REQ-105 — Provider Integration Internal HTTP Endpoint Audit

Last updated: 2026-07-09

## Scope

This audit checks whether the Provider Integration HTTP commands documented in
`docs/08-contracts/api/provider-integration.md` have production callers:

- `POST /api/v1/internal/provider-reservations`
- `POST /api/v1/internal/provider-reservations/{segmentBookingId}/cancel`

The audit covered the full repository, including service source, outbound HTTP
clients, e2e scripts, loadgen, deploy scripts/manifests, and contract/domain
cross-references.

## Conclusion

**Conclusion (a): no production caller was found.** The endpoints are currently
implemented and unit-tested inside `services/provider-integration`, but the
production reservation/cancellation path is event-driven:

- Booking Orchestration publishes `SegmentReservationRequested` and
  `SegmentBookingCancelled` events.
- Provider Integration subscribes to `events:booking-orchestration` and handles
  those events through `application.NewInboundEventHandler(...)`.
- Booking Orchestration consumes Provider Integration outcome events such as
  `ProviderReservationConfirmed` from `events:provider-integration`.

The contract text currently says these internal HTTP endpoints are "called by
Booking Orchestration", but the implementation does not contain such a caller.
No contract text was found that reserves these HTTP endpoints as an external
operations escape hatch.

## Evidence Chain

### Endpoint implementation exists only in Provider Integration

- `services/provider-integration/internal/http/handler.go` registers:
  - `POST /api/v1/internal/provider-reservations`
  - `POST /api/v1/internal/provider-reservations/:segmentBookingId/cancel`
- `services/provider-integration/internal/http/api_test.go` calls those routes
  via `httptest` for local handler coverage. These are not production callers.

### Production event path is wired

- `services/provider-integration/cmd/provider-integration/main.go` starts a
  Redis subscriber and wires it to
  `application.NewInboundEventHandler(service)`.
- `services/provider-integration/internal/adapters/messaging/subscriber.go`
  defaults Provider Integration subscriptions to:
  - `events:booking-orchestration`
  - `events:supplier-catalog`
- `services/provider-integration/internal/application/inbound_events.go`
  handles:
  - `SegmentReservationRequested` by calling `RequestReservation(...)`
  - `SegmentBookingCancelled` by calling `CancelReservation(...)`
- `services/booking-orchestration/src/main/java/com/trainticket/bookingorchestration/application/BookingOrchestrationService.java`
  maps domain events to cross-context events:
  - `SegmentReservationRequested`
  - `SegmentBookingCancelled`
- `services/booking-orchestration/src/main/java/com/trainticket/bookingorchestration/adapters/messaging/RedisEventBusInitializer.java`
  subscribes Booking Orchestration to `events:provider-integration` for Provider
  Integration outcomes.

### Full-repository caller search found no HTTP caller

Representative reproducible searches from repository root:

```bash
git grep -n -I -E 'provider-reservations|/api/v1/internal/provider|PROVIDER_INTEGRATION|ProviderIntegration|providerIntegration|provider-integration' -- deploy services platform docs scripts Makefile service-catalog.json project-index.yaml go.work
```

Findings:

- The concrete `provider-reservations` paths occur in:
  - the Provider Integration API contract,
  - the Provider Integration HTTP handler,
  - Provider Integration HTTP unit tests.
- No occurrence was found in Booking Orchestration production source, other
  services, e2e scripts, loadgen, or deploy scripts as an outbound HTTP call.

```bash
git grep -n -I -E 'RestTemplate|WebClient|HttpClient|java\.net\.http|OkHttp|fetch\(|axios|requests\.|urllib|reqwest|ureq|net/http|http\.Client|http\.NewRequest|curl |wget ' -- services deploy scripts platform
```

Findings:

- Generic outbound HTTP references exist in e2e scripts, Legacy ACL, Post Sales
  configuration, platform/runtime code, and tests.
- None target `provider-integration`, `/api/v1/internal/provider-reservations`,
  or the cancellation path.

```bash
git grep -n -I -E 'provider-reservations|provider-integration:8080|events:booking-orchestration|SegmentReservationRequested|SegmentBookingCancelled' -- deploy/loadgen deploy/e2e services/booking-orchestration services/provider-integration docs/08-contracts docs/02-domains/provider-integration.md docs/01-ddd-high-level/context-map.md docs/03-ddd-final/phase-1-contract.md
```

Findings:

- `deploy/loadgen` reads `events:booking-orchestration` for observation, but
  does not call Provider Integration HTTP.
- `deploy/e2e` inspects booking events and calls other HTTP APIs, but does not
  call Provider Integration reservation/cancel HTTP endpoints.
- Contract and domain documents describe Provider Integration through
  reservation/cancellation concepts and event subscriptions; no operations-only
  HTTP escape-hatch wording was found.

## Contract Cross-Reference

- `docs/08-contracts/api/provider-integration.md` documents the two internal
  HTTP endpoints and states they are called by Booking Orchestration.
- `docs/08-contracts/events/booking-orchestration.md` lists Provider Integration
  as a consumer of `SegmentReservationRequested` and `SegmentBookingCancelled`.
- `docs/08-contracts/messaging.md` includes the edge
  `events:booking-orchestration` → `provider-integration` for
  `SegmentReservationRequested`.
- `docs/03-ddd-final/phase-1-contract.md` says reservation and cancellation
  proceed through `SegmentReservationRequested` and `SegmentBookingCancelled`.

These cross-references support the event-driven path and conflict with the API
contract's caller statement.

## Provider Integration HTTP Surface Deep Check

Actual Provider Integration HTTP routes are:

- Standard runtime endpoints from `platform/go-runtime`:
  - `GET /healthz`
  - `GET /readyz`
  - plus runtime-compatible aliases `GET /health`, `GET /live`, `GET /livez`,
    `GET /ready`, and `GET /metadata`
- Provider Integration commands:
  - `POST /api/v1/internal/provider-reservations`
  - `POST /api/v1/internal/provider-reservations/:segmentBookingId/cancel`

The two command endpoints conform to the documented shape:

- Request/response JSON uses camelCase fields.
- Status enums use SCREAMING_SNAKE_CASE.
- Reservation returns `202` with `segmentBookingId`, `status`, and optional
  `providerReference`.
- Cancellation returns `200` with `segmentBookingId` and
  `cancellationStatus`.
- `Idempotency-Key` is required by middleware and validated as UUID v7.
- Unknown JSON fields are rejected by `DisallowUnknownFields`, including the
  event-only `idempotencyKey` body field.
- Error bodies use the canonical `code`, `message`, `correlationId`, `details`
  shape.

Minor noted mismatch: the Provider Integration contract lists only endpoint-
specific error codes (`VALIDATION_FAILED`, `UNAVAILABLE`, `NOT_FOUND`), while
shared HTTP conventions also allow cross-cutting `IDEMPOTENCY_KEY_REUSED` and
`DOMAIN_RULE_VIOLATION`; the implementation can emit those shared codes.

## Recommendation for Orchestrator Decision

Because no caller was found, choose one of these before changing behavior:

1. **Delete the dead HTTP command endpoints and remove them from the API
   contract.** This aligns the service boundary with the production event path
   and avoids maintaining unused synchronous commands.
2. **Keep the endpoints as an internal operations escape hatch and update the
   contract.** If retained, the contract should explicitly say they are not part
   of the normal Booking Orchestration production flow, require controlled
   internal/ops access, and define when manual reservation/cancellation replay is
   allowed.

No endpoint deletion or behavior change was made in this audit branch pending
that decision.
