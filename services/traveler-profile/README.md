# traveler-profile

Domain: Traveler Profile

Language: java

Phase: phase-1-support

Status: skeleton

Published outbox cleanup uses `idx_outbox_published_at` from migration `005_outbox_retention_index.sql`.
The index supports the existing thirty second cutoff and one thousand row cleanup batch without changing event publication or business state.

HTTP requests use Java virtual threads through `spring.threads.virtual.enabled=true`.
Requests waiting for their bodies or JDBC connections can release their carrier threads while other HTTP requests are handled.
Database operations and readiness still use the existing Hikari pool and dependency checks.

`HttpRequestConcurrencyLiveTest` imports a recorded traveler snapshot into an isolated PostgreSQL schema and reads it through the real HTTP API.
It creates a traveler using the recorded account, name, traveler type and contact fields, then holds 220 idempotent replays with incomplete request bodies.
After all 220 requests enter the servlet filter chain, health, readiness and both traveler reads must succeed within two seconds per request.
Completing the bodies must return the original 201 response for every replay, preserve the recorded snapshot and create no additional travelers or outbox events.
The test observer only counts incoming requests; HTTP handling, PostgreSQL persistence and Redis outbox publication use the application implementations.

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

Run the recorded tests with Java 25, local PostgreSQL and a dedicated test Redis database.
The snapshot and identity event inputs are previously captured records kept outside version control.
The test generates only a transport idempotency key and the application's normal creation identifiers.

```bash
export TEST_DATABASE_URL='jdbc:postgresql://localhost:15432/postgres?user=postgres'
export TEST_DATABASE_URI='postgresql://postgres@localhost:15432/postgres'
export TEST_REDIS_URL='redis://localhost:16379/14'
export TEST_TRAVELER_SNAPSHOT="$PWD/../../.scratch/runtime-fixes/goal-traveler-concurrency-two-facts.json"
export TEST_IDENTITY_FILTER_EVENTS="$PWD/../../.scratch/runtime-fixes/goal-traveler-filter-events.json"
mvn -Dtest=HttpRequestConcurrencyLiveTest,IdentityFactConcurrencyLiveTest,IdentityFilterDatabaseLiveTest test
```
