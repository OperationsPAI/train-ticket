# trip-planning

Domain: Trip Planning

Language: python

Phase: phase-1-core

Status: skeleton

## Owns

- TripIntent
- Itinerary
- SearchResult
- PriceHint

## DDD Sources

- `docs/02-domains/trip-planning.md`

## Language Rationale

Python is a better fit for search heuristics, ranking, and explanation logic.

## Skeleton Check

```bash
PYTHONPATH=src python3 -m unittest discover -s tests
```
