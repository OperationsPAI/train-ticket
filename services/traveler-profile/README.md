# traveler-profile

Domain: Traveler Profile

Language: java

Phase: phase-1-support

Status: skeleton

Published outbox cleanup uses `idx_outbox_published_at` from migration `005_outbox_retention_index.sql`.
The index supports the existing thirty second cutoff and one thousand row cleanup batch without changing event publication or business state.

## Owns

- TravelerProfile
- Document
- EligibilitySummary
- PreferenceSnapshot

## DDD Sources

- `docs/02-domains/traveler-profile.md`

## Language Rationale

Java keeps PII-heavy profile aggregates and validation policies explicit.

## Skeleton Check

```bash
mvn test
```
