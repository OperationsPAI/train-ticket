# offer-management

Domain: Offer Management

Language: TypeScript

Phase: phase-1-offer-domain-foundation

Status: implemented-foundation for `REQ-009-Offer-Management-domain-foundation`

## Owns in this slice

- `Offer` identity, version, lifecycle, validity window, and expiration behavior.
- `OfferItem` composition for train segment quote lines.
- Immutable `ItineraryReference` from Trip Planning.
- Immutable `AvailabilitySnapshotReference` from Capacity & Availability.
- Immutable `PriceSnapshot` and `FareRuleSnapshotReference` from Fare & Pricing.
- `PassengerMix` and traveler-set hash used to prevent stale passenger substitutions.
- `RiskDisclosure` records that must be accepted before order creation.
- Domain facts `OfferQuoted` and `OfferExpired` for downstream Journey Order and Notification consumers.

## Explicitly does not own

Offer Management does **not** lock inventory and does **not** create orders, payment intents, holds, reservations, or entitlements. The domain events include a `BoundaryProof` showing no mutation of:

- `CapacityHold`
- `PaymentIntent`
- `JourneyOrder`
- `Entitlement`

## DDD Sources

- `docs/02-domains/offer-management.md`
- `docs/03-ddd-final/phase-1-contract.md`

## Validation

```bash
npm test
```

The test command compiles TypeScript into ignored `test-output/` files and runs Node's built-in `node:test` suite, including Fastify inject smoke tests for `/health`, `/livez`, and `/readyz`.
