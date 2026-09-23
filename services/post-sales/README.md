# Post Sales

Java service for commercial post sales cases, PostgreSQL persistence, recorded downstream operations and Redis event processing.

Last updated: 2026-09-23.

## Change execution

Policy evaluation uses the accepted order snapshots returned during adjustment quote resolution, including actual departure times, traveler types and financial totals.
`AcceptedPolicyContextLiveTest` exercises a real order and fare quote against isolated PostgreSQL with an empty asynchronous policy projection.
The event projection remains available for event processing; its arrival is independent of synchronous evaluation.
Policy quote reads do not hold a database transaction while calling another service.
The decision write rechecks scope and target under the order lock, and concurrent evaluations share the persisted decision.
The PostgreSQL policy test observes real outbound requests to verify transaction absence and checks eight concurrent evaluations publish one decision.

`POST /api/v1/post-sales-cases/{caseId}/execute-change` advances an approved change through replacement reservation, difference payment, ticket issuance, original ticket release, refund settlement and order application.
Accepted operations are recorded in `changeOperations` and use stable downstream idempotency keys.
The case API includes issue authorization and settlement reads for downstream validation.
An unsuccessful uncaptured difference payment cancels replacement reservations, preserves original tickets and ends with `CHANGE_PAYMENT_TERMINATED` after compensation is verified.
Expiry is checked against the actual payment deadline, including capture requests rejected at the expiry boundary.
An expired target Offer with no replacement operations ends with `CHANGE_OFFER_EXPIRED` after original ticket validation.
Sold out replacement compensation records the rejection, voids replacement tickets, cancels replacement bookings, checks Hold release and returns captured difference payments.
It preserves original tickets and finishes with `CHANGE_CAPACITY_UNAVAILABLE` only after validation.
Case execution uses a PostgreSQL advisory lock to serialize downstream actions across replicas.
Sixteen independent cases can hold execution locks in a dedicated pool while ordinary queries and transactions use the separate business pool.
HTTP requests use Java virtual threads so waiting request bodies and downstream calls do not occupy a fixed servlet worker pool.
`HttpRequestConcurrencyLiveTest` holds 220 actual HTTP request bodies incomplete while checking case reads and health probes, then completes every request and verifies unchanged case data.
The lock pool is initialized before startup completes, and outbox publication starts after migrations complete.
`ChangeExecutionGuardLiveTest` verifies sixteen recorded case identities in parallel, cross session exclusion for the same case, business connection availability and lock release after exceptions.
The real database `CapacityCompensationLiveTest` verifies rejection persistence and execution lock release.
Loadgen's `TestLivePaidPartialChangeCompensates` verifies partial issuance and a captured difference payment through actual service APIs, with one available business seat for two travelers in an isolated test service.
See the [HTTP API](../../docs/08-contracts/api/post-sales.md) and [inventory integration requirements](../../docs/10-domain-enrichment/capacity-overbooking.md).

## Owns

- PostSalesCase lifecycle for cancellation, refund, change, rebook, and compensation case intake
- Immutable PostSalesDecision snapshots referencing Fare & Pricing evaluation and rule snapshots
- PostSalesExecutionPlan step ordering for entitlement, booking/segment, capacity, payment, and result-application requests
- PostSales domain events: PostSalesRequested, PostSalesEligibilityEvaluated, PostSalesDecisionQuoted, PostSalesApproved, PostSalesApplied

## Boundary

Post Sales coordinates refund and change cases through the owning services' APIs and events.
It does not execute payment channels or directly update another service's database.
Execution records preserve the downstream results and their business references.

## DDD Sources

- `docs/02-domains/post-sales.md`

## Language Rationale

Java keeps refund/change case workflows explicit and compatible with transaction review tooling.

## Check

```bash
mvn test
```

Loadgen's `TestLiveMultiTravelerPurchaseAndPostPurchase` checks issued replacements, refunds and other post purchase results through deployed APIs.
`TestLiveUnpaidChangePreservesOriginalTickets` exercises a recorded expired difference payment and verifies the completed compensation and loadgen retry state.
Live tests require a controlled test namespace and retain their business records for audit.
