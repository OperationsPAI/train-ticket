# Post Sales

Java service for commercial post sales cases, PostgreSQL persistence, recorded downstream operations and Redis event processing.

Last updated: 2026-09-23.

## Wallet complaint compensation

An approved `COMPENSATION` case credits the original order account with the frozen decision amount through Wallet / Promotion.
The evaluation response and case decision expose that amount as `compensationAmount`, with `refundableAmount` set to zero in the same currency.
`PostSalesApproved` carries `decisionKind=COMPENSATION`, `compensation.method=WALLET_CREDIT`, the approved amount and canonical `caseId`, with an empty `steps` array.
The original tickets, seat reservations, order and captured payment are preserved.
Payment skips refund processing for this approval, and Journey Order, Finance Settlement and Invoicing skip refund result processing for `PostSalesApplied` with `caseType=COMPENSATION`.

`POST /api/v1/post-sales-cases/{caseId}/execute-compensation` advances an evaluated, internally `EXECUTING` compensation case under the shared case execution guard.
The approved execution plan must contain `ISSUE_COMPENSATION_CREDIT` before an active case can issue wallet credit.
Public case details report `APPROVED` during execution and `APPLIED` after completion.
Each advancing call records one operation in `compensationOperations` or completes the case; callers use a new HTTP idempotency key for each advance.
The recorded `request` fixes the recipient, amount, rules and validity window before issuance.
It uses `benefitType=COMPENSATION_CREDIT`, `balanceType=PROMOTION_CREDIT`, `issuanceSource=POST_SALES_COMP`, `scopeType=ANY_TRIP` and the case business reason.
The current request sets `validFrom` to request creation time and `validUntil` to 30 days later, and reuses those timestamps on retry.
The `issued` record contains the checked wallet response, and `verified` contains `originalTicketsPreserved`, `originalPaymentPreserved`, `paymentIntentId`, `capturedAmount`, approved `amount` and `benefitId`.
Positive amounts require the benefit account, case, source, types, currency and issued amount to match the approved request.
Zero amounts retain `request` and `verified`, use an empty `verified.benefitId` and complete without a wallet issuance.

Each active advance checks that the original order is outside `CANCELLED`, `REFUNDED` and `FAILED`, scoped tickets belong to it and are not `VOIDED`, no capacity releases are recorded, and Payment returns zero refunds for the canonical case reference.
It also reads the original payment intent by order business reference and requires `CAPTURED` with zero `refundedAmount`.
After issuance, subsequent advances read the benefit again before recording verification or completing the case.
`completeCompensation` records `ISSUE_COMPENSATION_CREDIT` and `APPLY_RESULT` as `SUCCEEDED` in order, then the domain changes the case to `APPLIED` and emits `PostSalesApplied`.
The credit step records the benefit ID, or `zero-approved-compensation` for a zero amount, and the result step records the original order ID.
The executor preserves the original seat reservations through its wallet only actions; loadgen additionally compares original seat allocations, capacity Holds and paid purchase records.
PostgreSQL case snapshots retain the operation evidence for retries and restart recovery.
Wallet / Promotion also deduplicates `POST_SALES_COMP` issuance by `caseId`: an identical command returns the existing benefit, and a different command returns HTTP 409 `CONFLICT` at the wallet API.
`APPLIED` and `FAILED` compensation cases return their recorded state without further execution.
See the [HTTP API](../../docs/08-contracts/api/post-sales.md), [events](../../docs/08-contracts/events/post-sales.md) and [wallet issuance API](../../docs/08-contracts/api/wallet-promotion.md).

## Change execution

Capacity release consumption excludes completed release records before acquiring order locks.
The PostgreSQL history test holds the order lock in another transaction and verifies that an already completed release returns without waiting or changing history.

Policy evaluation uses the accepted order snapshots returned during adjustment quote resolution, including actual departure times, traveler types and financial totals.
`AcceptedPolicyContextLiveTest` exercises a real order and fare quote against isolated PostgreSQL with an empty asynchronous policy projection.
The event projection remains available for event processing; its arrival is independent of synchronous evaluation.
Policy quote reads do not hold a database transaction while calling another service.
The decision write rechecks scope and target under the order lock, and concurrent evaluations share the persisted decision.
The PostgreSQL policy test observes real outbound requests to verify transaction absence and checks eight concurrent evaluations publish one decision.

`POST /api/v1/post-sales-cases/{caseId}/execute-change` advances an approved change through replacement reservation, difference payment, ticket issuance, original ticket release, refund settlement and order application.
Accepted operations are recorded in `changeOperations` and use stable downstream idempotency keys.
The executor submits every traveler replacement before waiting for reservation confirmations.
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

Post Sales coordinates refund, change and wallet compensation cases through the owning services' APIs and events.
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

`CompensationMappingTest` checks the wallet compensation approval amount, method and empty execution actions.
Loadgen's `TestLiveMultiTravelerPurchaseAndPostPurchase` includes checks for issued replacements, refunds and wallet compensation through service APIs.
Its compensation branch checks the benefit, absence of case refunds, original tickets, confirmed capacity Holds, original order and captured payment, and selection of `compensation_credited` purchases for fulfillment.
`TestLiveWalletCompensationPreservesPaidTravel` contains a two traveler purchase with an ancillary item, equal wallet balance increment, preserved original seat and payment records, three case and wallet issuance replays with stable identities, registry reload and completed fulfillment.
That focused test has not yet been run against live services.
`TestLiveUnpaidChangePreservesOriginalTickets` exercises a recorded expired difference payment and verifies the completed compensation and loadgen retry state.
Live tests require a controlled test namespace and retain their business records for audit.
