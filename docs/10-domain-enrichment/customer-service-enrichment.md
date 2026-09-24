# Customer Service implementation

## Implementation

Customer Service is a TypeScript service with support cases, evidence, assignment, escalation, resolution, reopening and case timelines.
Escalation levels and priority based SLA policies track first response and resolution times, record breaches and can produce escalation or compensation offers.
The PostgreSQL runtime evaluates open cases every five seconds; the evaluation path also contains deterministic simulated handling outcomes.
Compensation offers enforce level based amount limits and move through offer, acceptance and issuance.
Upstream facts enrich case context, and Admin Audit outcomes update recorded manual action requests.

## Interfaces and sources

[HTTP API](../08-contracts/api/customer-service.md) and [HTTP routes](../../services/customer-service/src/app.ts) cover `/api/v1/support-cases`, evidence, assignment, escalation, SLA evaluation, manual action requests and compensation offers.
[Domain](../../services/customer-service/src/domain.ts), [application service](../../services/customer-service/src/application/customer-service.ts) and [persistent runtime](../../services/customer-service/src/adapters/storage/runtime.ts) define state transitions, event publication and scheduled evaluation.
[Service README](../../services/customer-service/README.md) identifies support case ownership.

## Boundaries

SLA targets and simulated resolution percentages are configured behavior and do not establish measured service performance.
Compensation issuance updates the local offer and publishes `CompensationIssued`; this method contains no Payment or Wallet execution call.
Amount limits check the supplied authorization level and do not by themselves establish operator identity or finance approval.
Manual action requests and case context do not directly change the owning order, payment, identity or transfer aggregates.
Deployment and verification evidence, including differences from workspace code, are recorded in [current status](../00-current-status.md).
Remaining work is maintained only in [TODO](../TODO.md).
