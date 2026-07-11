# Post-Sales Enrichment — Time-Based Refund & Change Policy Engine

## Context

**Service**: post-sales (Java, `services/post-sales/`)
**Current state**: 1,351-line domain with `PostSalesCase` aggregate, full state machine (Opened → Approved → ExecutionRequested → Applied/Failed/Rejected). Has change flow snapshots and decision models. Missing: actual refund fee calculation logic, time-based penalty tiers, change fare-difference computation, voluntary vs involuntary distinction.

**Key files**:
- `src/main/java/com/trainticket/postsales/domain/PostSalesCase.java` — aggregate root
- `src/main/java/com/trainticket/postsales/domain/PostSalesDecision.java`
- `src/main/java/com/trainticket/postsales/application/`

**DDD spec**: `docs/02-domains/post-sales.md`

## Requirements

### R1: Time-Based Refund Fee Tiers

Implement refund penalties based on time before departure (modeled after 12306 rules):

```
VOLUNTARY refund tiers:
  > 15 days before departure:  5% fee (minimum 2 CNY)
  8-15 days:                  10% fee
  2-7 days:                   20% fee
  < 48 hours:                 Non-refundable (only change allowed)
  After departure:            Non-refundable

INVOLUNTARY refund (carrier cancellation, disruption):
  Full refund, 0% fee, regardless of timing

SPECIAL:
  Student tickets:            0% fee if > 2 days before departure
  Group bookings (≥10):       5% fee flat (regardless of timing)
```

**Domain model changes**:
- New `RefundPolicyEngine` class with:
  - `evaluateRefund(originalOrder, requestTime, departureTime, involuntary, travelerType, groupSize) → RefundAssessment`
  - `RefundAssessment`: `refundableAmount`, `penaltyAmount`, `penaltyPct`, `tierApplied`, `explanation`
- `RefundTier` value object: `minDaysBefore`, `maxDaysBefore`, `penaltyPct`, `minimumPenalty`
- `RefundPolicy`: `tiers: List<RefundTier>`, `involuntaryOverride: boolean`, `specialRules: Map<String, RefundTier>`

### R2: Change (Rebooking) Fee & Fare Difference

When a passenger changes their ticket to a different date/train:

```
Change fee:
  > 48 hours before departure: free (1 free change per ticket)
  ≤ 48 hours: 20% of original fare
  2nd+ change: 20% regardless of timing

Fare difference:
  New fare > original: passenger pays difference
  New fare < original: difference refunded to original payment method
  Fare difference calculated AFTER applying change fee
```

**Domain model changes**:
- New `ChangePolicyEngine`:
  - `evaluateChange(originalOrder, newQuote, requestTime, departureTime, changeCount) → ChangeAssessment`
  - `ChangeAssessment`: `changeFee`, `fareDifference`, `netPayable`, `netRefundable`, `explanation`
- Track `changeCount` per order item (incremented on each successful change)
- `PostSalesCase` type `CHANGE` now computes real fees instead of pass-through

### R3: Refund Amount Waterfall

When processing a refund, amounts must be unwound in the correct order:

```
Waterfall:
  1. Ancillary services (insurance, meals, upgrades) — refundable if unused
  2. Service fees — platform fee retained, supplier fee returned
  3. Taxes — fully refundable
  4. Base fare — refundable minus penalty
  5. Discounts — proportionally clawed back

Example:
  Original: base=200 + tax=20 + service_fee=10 + insurance=50 = 280 CNY
  Penalty: 20% of base = 40 CNY
  Refund: (200-40) + 20 + 0 + 50 = 230 CNY (service fee retained)
```

**Domain model changes**:
- `RefundWaterfall` value object with ordered components
- `AmountDecisionSnapshot` enriched with component-level refund/retain decisions
- Each component has `refundable: boolean` and `retainReason: String`

### R4: Voluntary vs Involuntary Classification

The system must correctly classify refund reasons:

```
VOLUNTARY:
  - Customer-initiated cancellation
  - Change of plans
  - Duplicate booking

INVOLUNTARY:
  - Train cancelled by carrier
  - Significant delay (> 2 hours)
  - Force majeure
  - Platform error
```

**Domain model changes**:
- `RefundClassification` enum: `VOLUNTARY`, `INVOLUNTARY_CARRIER`, `INVOLUNTARY_DELAY`, `INVOLUNTARY_FORCE_MAJEURE`, `INVOLUNTARY_PLATFORM`
- Classification affects which `RefundTier` applies
- Involuntary refunds bypass penalty tiers entirely

## Interface Contracts

### Events consumed
- `events:journey-order` → `JourneyOrderCreated` — original order details for refund calculation
- `events:fare-pricing` → `FareQuoteComputed` — new fare for change comparison
- `events:disruption-recovery` → `DisruptionDeclared` — involuntary classification trigger

### Events produced
- `PostSalesDecisionQuoted` — enriched with `refundAssessment` or `changeAssessment` details
- `RefundFeeAssessed` — new event for fare-pricing audit trail
- `ChangeFeeAssessed` — new event with fare difference breakdown

## Test Criteria

1. Voluntary refund 20 days before departure → 5% penalty
2. Voluntary refund 3 days before departure → 20% penalty
3. Involuntary refund (carrier cancel) at any time → 0% penalty
4. Change with fare increase → passenger pays difference + change fee
5. Change with fare decrease → refund difference minus change fee
6. Second change on same ticket → 20% fee regardless of timing
7. Waterfall correctly retains service fee, refunds tax

## Files to Modify

- `src/main/java/.../domain/RefundPolicyEngine.java` — new
- `src/main/java/.../domain/ChangePolicyEngine.java` — new
- `src/main/java/.../domain/RefundTier.java` — new value object
- `src/main/java/.../domain/RefundWaterfall.java` — new
- `src/main/java/.../domain/RefundClassification.java` — new enum
- `src/main/java/.../domain/PostSalesCase.java` — integrate policy engines
- `src/main/java/.../domain/PostSalesDecision.java` — enrich with assessment
- `src/main/java/.../application/PostSalesApplicationService.java` — wire engines
- `src/test/java/.../domain/RefundPolicyEngineTest.java` — unit tests
- `src/test/java/.../domain/ChangePolicyEngineTest.java` — unit tests
