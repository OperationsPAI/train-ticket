# trip-planning

Domain: Trip Planning  
Language: Python  
Phase: `phase-1-search-foundation`  
Status: REQ-008 search foundation

Trip Planning turns a validated `TripIntent` and upstream service-plan-like references into deterministic, explainable itinerary candidates. It is a search-only domain slice: it does not create Offers, CapacityHolds, orders, payments, tickets, or provider calls.

## Owns in this slice

- `TripIntent` validation: origin/destination, departure time window, passenger count, transport modes, connection limits, and budget/preferences.
- `Itinerary` and `LegCandidate` primitives assembled from upstream `Place & Network` and `Service Plan` references.
- Deterministic `PlanningScore` components and exclusion explanations for auditable ranking.
- `PriceHint` and `AvailabilityHint` snapshots clearly marked non-authoritative: not offers, not fare freezes, and not inventory locks.
- FastAPI-safe `/search` adapter plus `search_itineraries(...)` application function returning candidate DTOs.

## Does not own

- Schedule, route topology, stop authority, capacity, final price, fare rules, Offers, holds, orders, payments, ticketing, fulfillment, or provider integration.

## DDD Sources

- `docs/02-domains/trip-planning.md`
- `docs/03-ddd-final/phase-1-contract.md`

## Local validation

```bash
PYTHONPATH=src python3 -m unittest discover -s tests
```
