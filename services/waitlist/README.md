# Waitlist

TypeScript service for durable candidate queues, exact capacity matching, automatic booking fulfillment, confirmation, cancellation and archival.
The current public interface is `/api/v1/waitlist-requests`.

## Automatic fulfillment

Enrollment resolves the requested trip from authoritative Payment, Journey Order, Offer Management, Fare Pricing, Trip Planning and Service Plan reads. The persisted intent includes the complete traveler set, scheduled service, service date, canonical class, station interval, stop-sequence version and a verified payment-guarantee snapshot.

Each `CapacityReleased` event is recorded once by its event ID. A candidate is eligible only when every match dimension agrees and the accumulated credit covers the whole traveler group; groups are never split. Promotion then checkpoints fare quote, commercial offer and new Journey Order creation. The offer window is the earliest of the commercial expiry, waitlist deadline and payment authorization deadline, and the verified guarantee must cover the offer total in the same currency.

Once an order is linked, the recovery worker automatically:

1. Waits for the order's Booking saga and successful risk progression.
2. Requests and verifies one confirmed reservation and capacity hold per traveler.
3. Creates and captures one PaymentIntent for the new order while the authorization is valid.
4. Issues one initial entitlement per traveler after payment capture.
5. Leaves the waitlist request `MATCHING` until Journey Order reports or emits `JourneyOrderConfirmed`; only then does Waitlist persist `FULFILLED` and publish `WaitlistFulfilled`.

## Recovery and exactly-once effects

The fulfillment checkpoint is stored in the waitlist row. Reservation IDs and all reservation, payment, entitlement, capacity-release and order-cancellation idempotency keys are deterministic UUIDv7 values for one promotion attempt. A process restart therefore reuses the same keys and first recovers durable downstream facts by segment-booking ID, order business reference and PaymentIntent ID.

If an attempt is safely terminated before settlement, Waitlist cancels an uncaptured payment, releases every known capacity hold and restores only the unmatched credit slots. A new promotion attempt receives a fresh set of operation keys. If payment is already captured, an entitlement exists, or capture wins a cancellation race, the request is kept recoverable and its capacity is not released. Linked suspended requests are not expired by the ordinary deadline sweep and can still converge from an authoritative order confirmation.

The runtime worker drains both incomplete promotion and fulfillment work. `WAITLIST_WORK_SCAN_MS` configures its interval; `WAITLIST_PROMOTION_SCAN_MS` remains a fallback for existing deployments.

Cancellation and confirmation consumers lock the matching order row. Replayed confirmations are idempotent, and archived confirmation replays do not emit a second success event.

See [HTTP API](../../docs/08-contracts/api/waitlist.md) and [implementation notes](../../docs/10-domain-enrichment/waitlist-impl.md) for the wider domain contracts.

## Build and verify

```bash
npm ci --install-links
npm run build
npm test
```

The Docker build compiles `src/main.ts` to `dist/main.js` and starts it with Node.
Runtime requires PostgreSQL, Redis and configured downstream service URLs.
The migration runner applies `migrations/` before readiness succeeds.

PostgreSQL confirmation, query-plan and transaction-rollback restart tests require `TEST_DATABASE_URL` and their individual source-file variables.
Online client tests require `TEST_CLIENT_BASE`, `TEST_OFFER_BINDING_SAMPLE` and `TEST_OFFER_ITINERARY`.
Tests that lack external prerequisites are reported as skipped.
