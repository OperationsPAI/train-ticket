# service-plan

Domain: Service Plan

Language: golang

Phase: phase-1-core

Status: tested domain foundation (REQ-005)

## Owns

- ServicePlan and PlanVersion lifecycle for publishable schedule plans.
- ServicePattern, ServiceStop, and ordered stop sequences.
- Calendar operating rules and planned add/suspend exceptions.
- Timetable planned stop times, timezone, and explicit cross-day offsets.
- ScheduledService materialized from published plan versions.
- ServiceSegment references derived from ordered stop sequences.
- Seasonal schedule variants, rush-period temporary trains, dated service cancellations, and delay propagation facts.

## Does Not Own

- Place, TransportNode, topology, or timezone master data; this context stores
  only Place & Network identifiers and node snapshot versions.
- Capacity, seats, quotas, holds, or sellability decisions beyond publishing schedule-period capacity multipliers for downstream contexts.
- Fare, tax, rule, order, entitlement, payment, post-sales, or realtime
  disruption state.

## Delivered Foundation

REQ-005 adds a coherent domain model in `internal/domain/service_plan.go` with
validation for:

- minimum viable ServicePattern stop counts and strictly increasing stop order;
- calendar exception conflicts between planned add-service and suspension facts;
- timetable ordering and explicit cross-day semantics via day offsets;
- PlanVersion validation, publication, overlap checks, and published-version
  immutability;
- ScheduledService materialization only from published versions on operating
  dates; and
- ServiceSegment derivation without inventory, fare, order, or disruption data.

Focused tests in `internal/domain/service_plan_test.go` cover these invariants.

## DDD Sources

- `docs/02-domains/service-plan.md`
- `docs/03-ddd-final/phase-1-contract.md`

## Language Rationale

Go keeps schedule query services small, fast, and easy to operate.

## Validation

```bash
go test ./...
```


## Seasonal schedules and real-time operations

REQ-312 enriches the service-plan domain with production timetable operations:

- Built-in schedule periods: `REGULAR`, `SPRING_RUSH`, `SUMMER_RUSH`, `NATIONAL_DAY`, and `LABOR_DAY` with the required capacity multipliers.
- Temporary rush-period trains (`L*` train numbers), including stops subsets and available travel classes, exposed via `POST /api/v1/scheduled-services/{serviceRef}/temporary-services`.
- Delay recording via `POST /api/v1/scheduled-services/{serviceRef}/delays`; downstream stops receive dampened delay events at two minutes recovery per subsequent stop.
- Dated cancellation/restoration via `/cancellations` and `/restorations`; responses expose whether the service-date remains bookable.
- Rush-period cleanup through the application service emits `TemporaryServiceExpired` after a configured period end.

Events continue to use the outbox publisher when the service is wired with Postgres:
`TemporaryServiceAdded`, `TrainDelayed`, `TrainCancelled`, `TrainRestored`, and `TemporaryServiceExpired` are appended in the same unit of work as persisted snapshot changes.
