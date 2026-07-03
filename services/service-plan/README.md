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

## Does Not Own

- Place, TransportNode, topology, or timezone master data; this context stores
  only Place & Network identifiers and node snapshot versions.
- Capacity, seats, quotas, holds, or sellability decisions.
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
