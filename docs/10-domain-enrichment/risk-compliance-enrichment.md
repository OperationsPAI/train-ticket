# Risk and Compliance implementation

## Implementation

Risk and Compliance is a Python service with assessment, blocking and evaluation logic.
`RiskComplianceService` handles order, payment, post sales and account assessments using the `ALLOW`, `DENY`, `CHALLENGE` and `HOLD` decisions and scores from 0 to 1000.
`RiskEvaluationService` provides a separate score from 0 to 100 with `PASS`, `CHALLENGE` and `BLOCK` verdicts, velocity counters, weighted signals, pattern matching and recorded staff overrides.
The runtime wires PostgreSQL repositories and Redis velocity counters, and subscribes to Journey Order, Booking Orchestration and Identity Verification events.
Identity purchase limit facts feed risk evaluation, while booking assessment results and block decisions are published for downstream handling.

## Interfaces and sources

[Assessment API](../08-contracts/api/risk-compliance.md) covers `/api/v1/risk-assessments` and `/api/v1/risk-blocks/lift`.
[HTTP routes](../../services/risk-compliance/src/risk_compliance/api.py) also expose creation and retrieval under `/api/v1/risk-evaluations` and an evaluation `/override` operation.
[Assessment application](../../services/risk-compliance/src/risk_compliance/application.py), [evaluation service](../../services/risk-compliance/src/risk_compliance/evaluation.py), [domain](../../services/risk-compliance/src/risk_compliance/domain.py) and [subscriber](../../services/risk-compliance/src/risk_compliance/adapters/messaging/redis_streams.py) define current behavior.
[Service README](../../services/risk-compliance/README.md) identifies the service and its ownership.

## Boundaries

The two scoring interfaces have separate policies and response vocabularies.
Evaluation contains account, payment and refund history handlers, but the default subscriptions contain only the three streams listed above.
An evaluation override updates that evaluation; order block lifting is a separate operation.
Risk decisions do not themselves change payment, capacity or ticket state.
Deployment and verification evidence, including differences from workspace code, are recorded in [current status](../00-current-status.md).
Remaining work is maintained only in [TODO](../TODO.md).
