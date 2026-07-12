# traveler-profile

Domain: Traveler Profile

Language: java

Phase: phase-1-support

Status: skeleton

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
