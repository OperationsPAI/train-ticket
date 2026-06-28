# customer-service

Domain: Customer Service

Language: typescript

Phase: phase-1-support

Status: skeleton

## Owns

- SupportCase
- EvidenceRef
- ManualActionRequest
- CaseTimeline

## DDD Sources

- `docs/02-domains/customer-service.md`

## Language Rationale

TypeScript fits collaborative case APIs and UI-adjacent timelines without owning target state.

## Skeleton Check

```bash
npm test
```
