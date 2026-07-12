# admin-audit

Domain: Admin & Audit

Language: java

Phase: phase-1-support

Status: skeleton

## Owns

- OperatorIdentity
- Approval
- ManualAction
- AuditTrail

## DDD Sources

- `docs/02-domains/admin-audit.md`

## Language Rationale

Java is a conservative fit for approvals, audit retention, and authorization boundaries.

## Skeleton Check

```bash
mvn test
```
