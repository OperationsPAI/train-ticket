# service-plan

Domain: Service Plan

Language: golang

Phase: phase-1-core

Status: skeleton

## Owns

- Route
- ServicePlan
- Calendar
- Timetable
- PlanVersion

## DDD Sources

- `docs/02-domains/service-plan.md`

## Language Rationale

Go keeps schedule query services small, fast, and easy to operate.

## Skeleton Check

```bash
go test ./...
```
