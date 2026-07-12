# reporting

Domain: Reporting

Language: python

Phase: phase-1-limited

Status: skeleton

## Owns

- MetricDefinition
- DashboardReadModel
- FunnelView

## DDD Sources

- `docs/02-domains/reporting.md`

## Language Rationale

Python is a pragmatic fit for metric definitions, event-derived views, and analytics validation.

## Skeleton Check

```bash
PYTHONPATH=src python3 -m unittest discover -s tests
```
