# Fare Pricing implementation

## Implementation

Fare Pricing is a Python service that publishes versioned fare rules and records fare quotes, rule snapshots, price explanations and refund or change adjustment quotes.
The calculation supports distance or flat base fares, seat class multipliers, advance purchase tiers, peak adjustments, taxes, fees, eligible discounts and capacity adjustments.
Capacity pricing reads local `CapacitySnapshotUpdated` projections by segment and departure date.
Eligibility discounts retain the traveler, eligibility type and certificate reference in `eligibilityUsages`.
Change adjustments compare the recorded original and target quotes and check traveler, currency, channel and target validity.

## Interfaces and sources

[HTTP API](../08-contracts/api/fare-pricing.md) documents fare rule publication, `/api/v1/fare-quotes` and `/api/v1/adjustment-quotes`.
[Request schemas](../../services/fare-pricing/src/fare_pricing/web/schemas.py) and [HTTP handlers](../../services/fare-pricing/src/fare_pricing/web/handlers.py) define the accepted fields, including `seatClass`, `distanceKm` and `departureTime`.
[Pricing domain](../../services/fare-pricing/src/fare_pricing/domain.py), [application service](../../services/fare-pricing/src/fare_pricing/application/service.py) and [event handlers](../../services/fare-pricing/src/fare_pricing/handlers.py) contain the calculation and projection logic.
[Service README](../../services/fare-pricing/README.md) describes ownership.

## Boundaries

Distance pricing requires a configured distance rate and a supplied distance; otherwise the base fare is flat.
Advance purchase and peak adjustments require departure time, and capacity adjustments depend on available local snapshots.
A quote does not reserve inventory or eligibility uses and does not execute an order or payment.
Deployment and verification evidence, including differences from workspace code, are recorded in [current status](../00-current-status.md).
Remaining work is maintained only in [TODO](../TODO.md).
