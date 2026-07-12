# Travel Insurance Enrichment — Multi-Product & Claims Processing

## Context

**Service**: travel-insurance (Python, `services/travel-insurance/`)
**Current state**: 484-line domain, basic policy issuance. No premium calculation, no claims processing, no auto-payout.

## Requirements

### R1: Insurance Product Catalog

```
Products:
  DELAY_INSURANCE:
    coverage: train delay > 60 minutes
    premium: 3 CNY per ticket
    payout: 30 CNY flat
    auto_payout: true (verified via delay events)

  CANCELLATION_INSURANCE:
    coverage: voluntary cancellation within 24h of departure
    premium: ticket_price * 5%
    payout: ticket_price * 80% (minus standard refund)
    auto_payout: false (requires claim)

  ACCIDENT_INSURANCE:
    coverage: personal injury during travel
    premium: 5 CNY per ticket
    payout: up to 500,000 CNY
    auto_payout: false (requires documentation)

  BAGGAGE_INSURANCE:
    coverage: lost or damaged baggage
    premium: 2 CNY per ticket
    payout: up to 2,000 CNY
    auto_payout: false (requires claim)
```

**Domain model**:
- `InsuranceProduct`: `productId`, `productType`, `premiumCalculation`, `coverageDescription`, `maxPayout`, `autoPayoutEnabled`
- `PremiumCalculator`: (product, ticketPrice, routeDistance) → premiumAmount

### R2: Policy Lifecycle

```
Policy statuses:
  ISSUED:     policy active, coverage in effect
  CLAIMED:    claim submitted, under review
  PAID_OUT:   claim approved, payout completed
  REJECTED:   claim denied
  EXPIRED:    travel completed without claim
  CANCELLED:  policy cancelled (ticket refunded before travel)

Auto-cancellation:
  - Ticket fully refunded → cancel insurance policy → refund premium
  - Ticket changed → adjust policy dates
```

**Domain model**:
- `InsurancePolicy` aggregate: `policyId`, `orderId`, `productRef`, `status`, `premiumPaid`, `coverageStart`, `coverageEnd`
- State machine: ISSUED → CLAIMED/EXPIRED/CANCELLED; CLAIMED → PAID_OUT/REJECTED

### R3: Auto-Payout for Delay Insurance

```
Flow:
  1. TrainDelayed event received with delayMinutes > 60
  2. Find all active DELAY_INSURANCE policies for that segment+date
  3. For each matching policy:
     a. Verify policy is ISSUED and coverage period matches
     b. Create auto-claim with delay evidence
     c. Approve claim automatically
     d. Trigger payout (30 CNY to original payment)
     e. Transition to PAID_OUT
```

**Domain model**:
- `InsuranceClaim`: `claimId`, `policyId`, `claimType`, `evidenceRefs`, `amount`, `status`
- `AutoClaimProcessor`: matches delay events to policies, creates auto-claims
- Events: `InsuranceClaimCreated`, `InsuranceClaimApproved`, `InsurancePayoutCompleted`

### R4: Manual Claims Workflow

```
For non-auto products (CANCELLATION, ACCIDENT, BAGGAGE):
  1. Passenger submits claim via API
  2. Claim enters PENDING review
  3. Admin reviews evidence (simulated: auto-approve after 24h)
  4. Approved → payout; Rejected → notify with reason

API:
  POST /api/v1/claims
    { policyId, claimType, description, evidenceRefs }
  GET /api/v1/claims/{claimId}
  POST /api/v1/claims/{claimId}/approve
  POST /api/v1/claims/{claimId}/reject
```

## Events Consumed
- `events:disruption-recovery` → `TrainDelayed` (auto-payout trigger)
- `events:post-sales` → `PostSalesApplied` (policy cancellation on refund)
- `events:journey-order` → `JourneyOrderCreated` (associate policy with order)

## Events Produced
- `InsurancePolicyIssued`
- `InsurancePolicyCancelled`
- `InsuranceClaimCreated`
- `InsurancePayoutCompleted`

## Test Criteria

1. Buy delay insurance for 3 CNY → policy ISSUED
2. Train delayed 90 min → auto-claim created, 30 CNY payout
3. Ticket refunded → insurance policy cancelled, premium refunded
4. Manual claim submitted → enters PENDING, approved after review

## Files to Modify

- `services/travel-insurance/src/travel_insurance/domain.py`
- `services/travel-insurance/src/travel_insurance/application/`
- `services/travel-insurance/src/travel_insurance/api.py`
- `migrations/`
