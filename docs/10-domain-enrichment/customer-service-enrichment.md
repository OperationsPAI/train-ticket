# Customer Service Enrichment — Ticket Escalation & SLA Tracking

## Context

**Service**: customer-service (Java, `services/customer-service/`)
**Current state**: 1,244-line domain with basic case creation. No escalation workflow, no SLA tracking, no compensation authorization.

## Requirements

### R1: Ticket Escalation Workflow

```
Escalation levels:
  L1_AGENT:      first-line support (handle common issues: refund status, ticket info)
  L2_SPECIALIST: complex cases (disputed charges, multi-leg issues, insurance claims)
  L3_SUPERVISOR: authorization-required (compensation > 200 CNY, policy exceptions)

Auto-escalation triggers:
  - L1 unresolved > 30 minutes → escalate to L2
  - L2 unresolved > 2 hours → escalate to L3
  - Customer requests escalation → immediate escalation
  - VIP customer (GOLD+ tier) → start at L2

Simulated resolution:
  - L1: auto-resolve 70% within 10s, escalate 30%
  - L2: auto-resolve 80% within 30s, escalate 20%
  - L3: auto-resolve 95% within 60s
```

**Domain model**:
- `SupportTicket` aggregate gains `escalationLevel`, `escalationHistory`
- `EscalationRule`: `fromLevel`, `triggerCondition`, `toLevel`
- Events: `TicketEscalated`, `TicketResolved`, `TicketReopened`

### R2: SLA Tracking

```
SLA targets by priority:
  URGENT (disruption, safety):  response 5min, resolution 30min
  HIGH (payment issue, refund):  response 15min, resolution 2h
  NORMAL (info request, change): response 1h, resolution 24h
  LOW (feedback, suggestion):    response 24h, resolution 72h

SLA breach actions:
  - Response SLA breached → auto-escalate + alert supervisor
  - Resolution SLA breached → alert management + compensation offer

Metrics:
  - Time to first response
  - Time to resolution
  - SLA compliance rate
```

**Domain model**:
- `SlaPolicy`: `priority`, `responseTargetMinutes`, `resolutionTargetMinutes`
- `SlaTracker`: tracks `firstResponseAt`, `resolvedAt`, checks against targets
- `SlaBreach`: `ticketId`, `breachType` (RESPONSE/RESOLUTION), `breachedAt`

### R3: Compensation Authorization

```
Authorization levels:
  L1 can authorize: up to 50 CNY (voucher/points)
  L2 can authorize: up to 200 CNY (cash refund)
  L3 can authorize: up to 1000 CNY (cash + voucher)
  Beyond 1000 CNY: requires finance approval

Compensation types:
  POINTS:   loyalty points credit
  VOUCHER:  future travel voucher
  CASH:     refund to payment channel
  UPGRADE:  complimentary seat upgrade on next trip
```

**Domain model**:
- `CompensationOffer`: `ticketId`, `type`, `amountMinor`, `authorizationLevel`, `status`
- `CompensationAuthorizer`: checks level against amount limit
- Events: `CompensationOffered`, `CompensationAccepted`, `CompensationIssued`

## Events Consumed
- `events:journey-order` → order details for case context
- `events:disruption-recovery` → auto-create tickets for affected passengers
- `events:post-sales` → link refund cases

## Events Produced
- `SupportTicketCreated`, `TicketEscalated`, `TicketResolved`
- `SlaBreach` — for monitoring/alerting
- `CompensationIssued` — for finance-settlement

## Test Criteria

1. Normal ticket unresolved > 30min → auto-escalated to L2
2. VIP customer → ticket starts at L2
3. L1 agent offers 100 CNY compensation → REJECTED (exceeds L1 limit)
4. SLA response breached → auto-escalate + alert
5. Ticket resolved within SLA → compliance tracked

## Files to Modify

- `src/main/java/.../customerservice/domain/` — escalation, SLA, compensation
- `src/main/java/.../customerservice/application/`
- `migrations/`
