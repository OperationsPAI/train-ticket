# Loyalty & Membership Enrichment — Points & Tier System

## Context

**Service**: loyalty-membership (Python, `services/loyalty-membership/`)
**Current state**: 539-line domain, basic member enrollment and tier lookup. No points earning, redemption, tier upgrade/downgrade logic, or expiry.

## Requirements

### R1: Points Earning Rules

```
Base earning:
  1 CNY spent = 1 point (二等座)
  1 CNY spent = 1.5 points (一等座)
  1 CNY spent = 2 points (商务座)

Bonus multipliers:
  Weekend travel: 1.5x
  Holiday travel: 2x
  Specific routes (京沪、京广): 1.2x
  Birthday month: 2x

Tier multiplier:
  SILVER: 1.0x
  GOLD:   1.2x
  PLATINUM: 1.5x
  DIAMOND: 2.0x

Example: Gold member, business class, 500 CNY, weekend
  = 500 * 2 (business) * 1.5 (weekend) * 1.2 (gold) = 1,800 points
```

**Domain model**:
- `PointsEarningRule`: `seatClassMultiplier`, `bonusConditions`, `tierMultiplier`
- `PointsCalculator`: pure function (fare, seatClass, travelDate, memberTier) → points
- `PointsTransaction`: `memberId`, `amount`, `type` (EARNED, REDEEMED, EXPIRED, ADJUSTED), `sourceRef`

### R2: Points Redemption

```
Redemption rates:
  100 points = 1 CNY discount (partial ticket payment)
  Minimum redemption: 500 points
  Maximum per ticket: 50% of fare payable by points
  Cannot redeem for: insurance, service fees, change fees

Redemption flow:
  1. User selects "use points" during payment
  2. System calculates max redeemable
  3. Points deducted → payment amount reduced
  4. If order cancelled → points restored
```

**Domain model**:
- `RedemptionRequest`: `memberId`, `orderId`, `pointsToRedeem`, `fareAmountMinor`
- `RedemptionResult`: `pointsDeducted`, `discountAmountMinor`, `remainingBalance`
- `RedemptionPolicy`: `pointsToCurrencyRate`, `minRedemption`, `maxFarePercentage`

### R3: Tier Upgrade/Downgrade

```
Tiers and qualification:
  SILVER:   0 - 2,999 qualifying points (default)
  GOLD:     3,000 - 9,999 OR 12+ trips per year
  PLATINUM: 10,000 - 24,999 OR 30+ trips per year
  DIAMOND:  25,000+ OR 60+ trips per year

Evaluation:
  - Calculated annually (calendar year)
  - Upgrade: immediate upon reaching threshold
  - Downgrade: at year end if not re-qualified
  - Grace period: 3 months into new year to re-qualify

Qualifying points:
  - Only earned from ticket purchases (not redemptions, not promotions)
  - Refunded tickets: qualifying points deducted
```

**Domain model**:
- `TierPolicy`: `tierName`, `minQualifyingPoints`, `minTrips`, `benefits`
- `TierEvaluator`: (yearlyQualifyingPoints, yearlyTrips) → tier
- `MembershipYear`: tracks qualifying points, trip count, current tier, evaluation date
- Events: `MemberTierUpgraded`, `MemberTierDowngraded`, `TierEvaluationCompleted`

### R4: Points Expiry

```
Expiry rules:
  - Points expire 24 months after earning
  - FIFO: oldest points expire first
  - Tier bonus: DIAMOND members get 36-month expiry
  - Expired points cannot be restored
  - Monthly batch job checks and expires points

Notification:
  - 30 days before expiry: notify member
  - Points about to expire shown separately in balance
```

**Domain model**:
- `PointsBalance` with `expiringBatches: List<{earnedAt, expiresAt, remaining}>`
- `expirePoints(now) → List<ExpiredBatch>`
- `getExpiringWithin(days) → {pointsAmount, expiryDate}`

## Events Consumed
- `events:journey-order` → `JourneyOrderCreated` (trip count)
- `events:payment` → `PaymentCaptured` (points earning trigger)
- `events:post-sales` → `PostSalesApplied` (deduct qualifying points on refund)

## Events Produced
- `PointsEarned` — memberId, points, sourceRef
- `PointsRedeemed` — memberId, points, orderId
- `PointsExpired` — memberId, points, batchId
- `MemberTierUpgraded` — memberId, oldTier, newTier
- `MemberTierDowngraded` — memberId, oldTier, newTier

## Test Criteria

1. Gold member, business, weekend, 500 CNY → 1,800 points earned
2. Redeem 1000 points → 10 CNY discount on 200 CNY fare
3. Redeem > 50% of fare → capped at 50%
4. Annual points reach 10,000 → immediate upgrade to PLATINUM
5. Year-end evaluation: points < 3,000 AND trips < 12 → downgrade to SILVER
6. Points earned 25 months ago → expired in monthly batch

## Files to Modify

- `services/loyalty-membership/src/loyalty_membership/domain.py` or equivalent
- `services/loyalty-membership/src/loyalty_membership/application/`
- `services/loyalty-membership/src/loyalty_membership/api.py`
- `migrations/`
