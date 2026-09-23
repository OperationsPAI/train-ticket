# Waitlist

TypeScript service for persisted candidate queues, order matching, confirmation, cancellation and archival.
The current public interface is `/api/v1/waitlist-requests`.

Creating a linked order leaves the candidate `MATCHING`.
Only `JourneyOrderConfirmed` produces `FULFILLED` and the success notification event.
Cancellation clears matching references and renews downstream operation keys for a new attempt, or expires the candidate when its deadline has passed.
Archived confirmation replays do not emit new success events.

See [HTTP API](../../docs/08-contracts/api/waitlist.md) and [implementation status](../../docs/10-domain-enrichment/waitlist-impl.md) for endpoints, verification and incomplete integration.
Automatic capacity triggered promotion and payment guarantee closure are not yet complete.

## Build and verify

```bash
npm ci --install-links
npm run build
```

The Docker build compiles `src/main.ts` to `dist/main.js` and starts it with Node.
Runtime requires PostgreSQL, Redis and configured downstream service URLs.
The migration runner applies `migrations/` before readiness succeeds.

Recorded PostgreSQL tests require `TEST_DATABASE_URL` and their individual source file variables.
Online client tests require `TEST_CLIENT_BASE`, `TEST_OFFER_BINDING_SAMPLE` and `TEST_OFFER_ITINERARY`.
Tests that lack external prerequisites are reported as skipped.
