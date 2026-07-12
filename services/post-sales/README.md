# post-sales

Domain: Post Sales

Language: java

Phase: phase-1-core

Status: REQ-014 domain foundation

## Owns

- PostSalesCase lifecycle for cancellation, refund, change, rebook, and compensation case intake
- Immutable PostSalesDecision snapshots referencing Fare & Pricing evaluation and rule snapshots
- PostSalesExecutionPlan step ordering for entitlement, booking/segment, capacity, payment, and result-application requests
- PostSales domain events: PostSalesRequested, PostSalesEligibilityEvaluated, PostSalesDecisionQuoted, PostSalesApproved, PostSalesApplied

## Boundary

Post Sales decides and orchestrates refund/change cases before external execution. It does not run payment channels, rewrite JourneyOrder internals, mutate entitlement state, or alter capacity ledgers directly. Execution steps are auditable requests/facts owned by their downstream domains.

## DDD Sources

- `docs/02-domains/post-sales.md`

## Language Rationale

Java keeps refund/change case workflows explicit and compatible with transaction review tooling.

## Check

```bash
mvn test
```
