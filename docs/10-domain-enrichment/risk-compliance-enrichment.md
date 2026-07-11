# Risk & Compliance Enrichment — Fraud Detection & Scalper Blocking

## Context

**Service**: risk-compliance (Java, `services/risk-compliance/`)
**Current state**: Scaffold only — 0 lines of domain logic. Has adapter/controller wiring but the risk evaluation is a pass-through. The DDD spec (`docs/02-domains/risk-compliance.md`) describes a full rules engine with velocity checks, device fingerprinting, and fraud scoring.

**Key files**:
- `src/main/java/com/trainticket/riskcompliance/` — service root
- Platform kit: `platform/java-kit/` for event publishing/consuming

**DDD spec**: `docs/02-domains/risk-compliance.md`

## Requirements

### R1: Velocity Rule Engine

Detect abnormal booking velocity that indicates scalper/bot activity:

```
Rule: VELOCITY_ACCOUNT_ORDER
  Condition: same accountId creates > 3 orders within 5 minutes
  Action: BLOCK order, emit RiskAlertRaised

Rule: VELOCITY_ACCOUNT_PAYMENT
  Condition: same accountId has > 5 payment attempts within 10 minutes
  Action: CHALLENGE (require additional verification)

Rule: VELOCITY_TRAVELER_BOOKING
  Condition: same travelerRef appears in > 2 orders within 1 hour
  Action: BLOCK order

Rule: VELOCITY_IP_ORDER
  Condition: same sourceIp creates > 10 orders within 15 minutes
  Action: BLOCK order, flag IP
```

**Domain model**:
- `VelocityRule` aggregate: `ruleId`, `dimension` (ACCOUNT, TRAVELER, IP, DEVICE), `threshold`, `windowSeconds`, `action` (PASS, CHALLENGE, BLOCK)
- `VelocityCounter` value object: sliding window counter per dimension key
- `RiskEvaluation` aggregate: `evaluationId`, `orderId`, `accountId`, `rules_triggered: List<RuleResult>`, `verdict` (PASS, CHALLENGE, BLOCK), `score`
- Use Redis sorted sets for sliding window counting (ZADD with timestamp scores, ZRANGEBYSCORE to count)

### R2: Risk Score Calculation

Compute a 0-100 risk score based on multiple signals:

```
Signal weights:
  velocity_score:        0-30 points (based on how close to velocity limits)
  account_age_score:     0-15 points (new account = high risk)
  traveler_mismatch:     0-20 points (traveler name ≠ account holder)
  high_value_order:      0-10 points (order > 5000 CNY)
  known_scalper_pattern: 0-25 points (multiple same-route bookings)

Thresholds:
  score < 30: PASS (no intervention)
  30 ≤ score < 60: CHALLENGE (require identity re-verification)
  score ≥ 60: BLOCK (reject order, human review required)
```

**Domain model**:
- `RiskSignal` value object: `signalType`, `rawValue`, `normalizedScore` (0-100 per signal)
- `RiskScoreCalculator`: pure function that takes `List<RiskSignal>` and weights → `totalScore`
- `ScoreThreshold` config: `passBelow`, `challengeBelow`, `blockAtOrAbove`

### R3: Scalper Pattern Detection

Detect patterns that match known scalper behaviors:

```
Pattern: SAME_ROUTE_BULK
  Condition: same accountId books same origin→destination > 3 times in 24h
  Score contribution: +25

Pattern: RAPID_SEARCH_THEN_BOOK
  Condition: account performs > 20 searches then books within 2 minutes
  Score contribution: +15

Pattern: MULTIPLE_DEPARTURE_DATES
  Condition: same route, same travelers, > 5 different departure dates in one session
  Score contribution: +20

Pattern: RESALE_REFUND_CYCLE
  Condition: accountId has > 3 refunds in past 7 days AND new booking within 1 hour of refund
  Score contribution: +30
```

**Domain model**:
- `ScalperPattern` value object: `patternType`, `detectionWindow`, `threshold`, `scoreContribution`
- `PatternMatcher`: consumes historical booking/refund data to detect patterns
- Patterns are evaluated asynchronously after order creation (not blocking the hot path)

### R4: Risk Evaluation API

The journey-order service calls risk-compliance to evaluate each new order:

```
POST /api/v1/risk-evaluations
{
  "orderId": "ord-xxx",
  "accountId": "acc-xxx",
  "travelerRefs": ["tvl-xxx"],
  "totalAmountMinor": 15000,
  "currency": "CNY",
  "route": {"origin": "node-shanghai", "destination": "node-beijing"},
  "departureDate": "2026-07-20",
  "sourceIp": "203.0.113.45",
  "channelId": "web"
}

Response:
{
  "evaluationId": "risk-eval-xxx",
  "verdict": "PASS" | "CHALLENGE" | "BLOCK",
  "score": 15,
  "triggeredRules": [
    {"ruleId": "VELOCITY_ACCOUNT_ORDER", "result": "PASS", "detail": "2/3 in window"}
  ],
  "recommendedAction": "PROCEED" | "VERIFY_IDENTITY" | "REJECT"
}
```

### R5: Event-Driven History Building

Consume events to build risk profiles:

- `events:journey-order` → `JourneyOrderCreated` — record order for velocity + pattern tracking
- `events:payment` → `PaymentCaptured` / `PaymentFailed` — record payment velocity
- `events:post-sales` → `PostSalesApplied` (refunds) — record for resale-refund pattern
- `events:account` → `AccountRegistered` — record account creation time for age scoring

## Interface Contracts

### Events consumed
| Stream | Event Type | Purpose |
|--------|-----------|---------|
| `events:journey-order` | `JourneyOrderCreated` | Velocity counting, pattern detection |
| `events:payment` | `PaymentCaptured`, `PaymentFailed` | Payment velocity |
| `events:post-sales` | `PostSalesApplied` | Refund-rebuy pattern |
| `events:account` | `AccountRegistered` | Account age scoring |

### Events produced
| Event Type | Trigger | Consumers |
|-----------|---------|-----------|
| `RiskEvaluationCompleted` | After every evaluation | journey-order (to lift/block), reporting |
| `RiskAlertRaised` | When score ≥ 60 or velocity breach | admin-audit, customer-service |
| `ScalperPatternDetected` | Async pattern match | admin-audit, reporting |

### API
- `POST /api/v1/risk-evaluations` — synchronous evaluation (< 50ms target)
- `GET /api/v1/risk-evaluations/{evaluationId}` — retrieve evaluation details
- `POST /api/v1/risk-evaluations/{evaluationId}/override` — staff override (lift block)

## Test Criteria

1. An account creating 4 orders in 5 minutes → BLOCK verdict
2. A new account (< 1 hour old) booking a high-value ticket → score ≥ 30, CHALLENGE
3. Normal user booking → score < 30, PASS
4. Same-route bulk booking (4x same route in 24h) → scalper pattern detected, score += 25
5. Staff override lifts a BLOCK → subsequent orders PASS (for that evaluation)
6. Redis sorted set correctly evicts entries outside the sliding window

## Files to Create/Modify

- `src/main/java/com/trainticket/riskcompliance/domain/` — all new:
  - `VelocityRule.java`, `VelocityCounter.java`
  - `RiskSignal.java`, `RiskScoreCalculator.java`
  - `RiskEvaluation.java` (aggregate root)
  - `ScalperPattern.java`, `PatternMatcher.java`
  - `RiskVerdict.java` (enum: PASS, CHALLENGE, BLOCK)
- `src/main/java/com/trainticket/riskcompliance/application/RiskEvaluationService.java`
- `src/main/java/com/trainticket/riskcompliance/adapters/api/RiskEvaluationController.java`
- `src/main/java/com/trainticket/riskcompliance/adapters/redis/VelocityRedisCounter.java`
- `src/main/java/com/trainticket/riskcompliance/adapters/messaging/RiskEventSubscriber.java`
- `migrations/002_risk_evaluation_tables.sql`
- `src/test/java/.../domain/RiskScoreCalculatorTest.java`
- `src/test/java/.../domain/VelocityRuleTest.java`
