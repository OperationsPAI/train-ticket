# Corporate Travel Enrichment — Approval Workflow & Budget Control

## Context

**Service**: corporate-travel (Python, `services/corporate-travel/`)
**Current state**: 438-line domain, basic agreement creation. No approval flow, no budget tracking, no policy enforcement.

## Requirements

### R1: Travel Policy Enforcement

```
Policy rules per corporate agreement:
  SEAT_CLASS_LIMIT:
    default: SECOND_CLASS only
    exception: VP+ level → FIRST_CLASS allowed
    override: trip > 6 hours → FIRST_CLASS allowed for all

  ADVANCE_BOOKING:
    minimum: 3 days before departure
    exception: emergency travel (requires manager approval)

  ROUTE_RESTRICTION:
    allowed_routes: list of approved O-D pairs
    require_approval_for_unlisted: true

  BUDGET_LIMIT:
    per_trip: 2,000 CNY
    per_employee_monthly: 8,000 CNY
    per_department_monthly: 100,000 CNY
```

**Domain model**:
- `TravelPolicy`: `agreementId`, `rules: List<PolicyRule>`
- `PolicyRule` variants: `SeatClassLimit`, `AdvanceBookingRule`, `RouteRestriction`, `BudgetLimit`
- `PolicyChecker`: (booking, employee, policy) → `PolicyResult` (COMPLIANT, NEEDS_APPROVAL, REJECTED)

### R2: Approval Workflow

```
Approval chain:
  Level 1: Direct manager (auto-approve if policy-compliant)
  Level 2: Department head (if budget > per_trip limit or policy exception)
  Level 3: Finance (if monthly department budget exceeded)

Workflow statuses:
  PENDING → APPROVED / REJECTED / ESCALATED
  
Auto-approval:
  - If all policies pass → auto-approve (no human in loop)
  - If only advance booking violated → escalate to Level 1
  - If budget exceeded → escalate to Level 2+

Simulation:
  - Simulated approvers auto-approve after 30-second delay
  - 10% rejection rate for realism
```

**Domain model**:
- `ApprovalRequest` aggregate: `requestId`, `bookingRef`, `employeeRef`, `currentLevel`, `status`, `history`
- `ApprovalDecision`: `level`, `approverRef`, `decision`, `reason`, `decidedAt`
- Events: `ApprovalRequested`, `ApprovalGranted`, `ApprovalRejected`, `ApprovalEscalated`

### R3: Budget Tracking

```
Budget dimensions:
  - Per employee per month
  - Per department per month
  - Per agreement per quarter

Budget operations:
  - Reserve on booking (tentative hold)
  - Commit on payment capture
  - Release on cancellation/refund

Budget alerts:
  - 80% utilized → warning notification
  - 100% utilized → block new bookings (require Level 3 approval)
```

**Domain model**:
- `BudgetPool`: `poolId`, `dimension` (EMPLOYEE/DEPARTMENT/AGREEMENT), `periodStart`, `periodEnd`, `limitMinor`, `reservedMinor`, `committedMinor`
- `BudgetReservation`: `reservationId`, `poolId`, `bookingRef`, `amountMinor`, `status` (RESERVED/COMMITTED/RELEASED)

### R4: Consolidated Billing

```
Monthly invoice generation:
  - Group all completed trips by department
  - Apply corporate discount rate
  - Generate line items: ticket cost, insurance, service fees
  - Deduct prepaid balance if applicable
  - Publish ConsolidatedInvoiceGenerated event for finance-settlement
```

**Domain model**:
- `MonthlyInvoice`: `invoiceId`, `agreementId`, `period`, `lineItems`, `totalMinor`, `discountMinor`
- `InvoiceLineItem`: `bookingRef`, `employeeName`, `route`, `travelDate`, `amountMinor`

## Events Consumed
- `events:journey-order` → budget commitment
- `events:payment` → budget finalization
- `events:post-sales` → budget release

## Events Produced
- `TravelPolicyChecked` — result, violations
- `ApprovalGranted` / `ApprovalRejected`
- `BudgetAlertTriggered` — threshold reached
- `ConsolidatedInvoiceGenerated`

## Test Criteria

1. Economy class booking, policy-compliant → auto-approved
2. First class booking by non-VP → NEEDS_APPROVAL
3. Monthly budget 90% utilized → warning alert
4. Monthly budget exceeded → booking blocked without Level 3 approval
5. Trip cancelled → budget reservation released

## Files to Modify

- `services/corporate-travel/src/corporate_travel/domain.py`
- `services/corporate-travel/src/corporate_travel/application/`
- `services/corporate-travel/src/corporate_travel/api.py`
- `migrations/`
