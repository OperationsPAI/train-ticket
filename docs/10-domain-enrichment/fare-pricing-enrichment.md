# Fare Pricing Enrichment — Dynamic Pricing Engine

## Context

**Service**: fare-pricing (Python, `services/fare-pricing/`)
**Current state**: 663-line domain with `Money`, `FareRule`, `FareRuleSet`, `FareQuote` models. Supports static rule matching and tax/fee/discount calculation. Missing: dynamic pricing tiers, revenue management signals, advance-purchase discounts, peak/off-peak modifiers.

**Key files**:
- `src/fare_pricing/domain.py` — core domain (Money, rules, quotes)
- `src/fare_pricing/service.py` — application service
- `src/fare_pricing/handlers.py` — event handlers
- `src/fare_pricing/api.py` — HTTP endpoints
- `src/fare_pricing/postgres.py` — storage adapter
- `migrations/` — SQL schema

**DDD spec**: `docs/02-domains/fare-pricing.md`

## Requirements

### R1: Advance Purchase Discount Tiers

Real train/airline systems price differently based on how far in advance the ticket is purchased. Implement a tiered discount system:

```
Tier 1: 21+ days before departure → 30% discount (早鸟价)
Tier 2: 14-20 days → 20% discount
Tier 3: 7-13 days → 10% discount
Tier 4: 3-6 days → base fare (无折扣)
Tier 5: 0-2 days → 20% surge (紧急票)
```

**Domain model changes**:
- Add `AdvancePurchaseTier` dataclass to `domain.py` with `min_days_before`, `max_days_before`, `multiplier`, `explanation_code`
- Add `advance_purchase_tiers` field to `FareRuleSet`
- In `FareQuote.calculate()`, determine tier from `departure_date - now()` and apply multiplier to base fare BEFORE tax/fee
- The tier hit must be recorded in `PriceExplanation` with code like `ADVANCE_PURCHASE_TIER_1`

### R2: Peak / Off-Peak Pricing

Certain departure times and dates command premium pricing:

```
Peak dates: National holidays (Spring Festival, National Day, Labor Day), weekends
Peak hours: 07:00-09:00, 17:00-19:00
Off-peak: All other times

Peak date surcharge: +30%
Peak hour surcharge: +15%
Off-peak discount: -10%
Combinable: peak date + peak hour = +45% (additive, not multiplicative)
```

**Domain model changes**:
- Add `PeakPricingRule` with `date_ranges: list[tuple[date,date]]`, `hour_ranges: list[tuple[int,int]]`, `date_surcharge_pct: int`, `hour_surcharge_pct: int`, `offpeak_discount_pct: int`
- Add a `peak_pricing` field to `FareRuleSet`
- `is_peak_date(departure_date)` and `is_peak_hour(departure_time)` helper methods
- Peak pricing applies AFTER advance purchase tier, BEFORE tax

### R3: Seat Class Multipliers

Different seat classes have different base fare multipliers:

```
SECOND_CLASS: 1.0x (base)
FIRST_CLASS: 1.6x
BUSINESS_CLASS: 2.8x
STANDING: 0.7x
SLEEPER_HARD: 1.8x
SLEEPER_SOFT: 2.5x
```

**Domain model changes**:
- Add `SeatClassMultiplier` enum/dataclass
- `FareRule` gets `seat_class_multipliers: dict[str, Decimal]`
- Applied FIRST, before advance purchase and peak pricing

### R4: Route Distance-Based Base Fare

Base fare should scale with route distance rather than being a flat amount:

```
per_km_rate: 0.15 CNY per km (二等座基准)
minimum_fare: 5.00 CNY
segments > 500km get 10% discount on the distance portion above 500km
```

**Domain model changes**:
- `FareRule` gets optional `per_km_rate`, `minimum_fare`, `distance_discount_threshold_km`, `distance_discount_pct`
- If `per_km_rate` is set and `distance_km` is provided in the quote request, compute base fare from distance
- Falls back to existing `base_fare` field if distance info not available

### R5: Capacity-Driven Dynamic Adjustment

When capacity-availability publishes remaining capacity signals, fare-pricing should adjust:

```
> 70% remaining: no adjustment
50-70% remaining: +5%
30-50% remaining: +15%
10-30% remaining: +30%
< 10% remaining: +50% (抢票溢价)
```

**Domain model changes**:
- Add `DynamicPricingBand` dataclass with `min_remaining_pct`, `max_remaining_pct`, `adjustment_pct`
- Consume `CapacitySnapshotUpdated` events from `events:capacity-availability` stream to maintain a local cache of remaining capacity per segment+date
- During quote calculation, look up cached capacity and apply the matching band
- This adjustment applies LAST, after all other price components

## Interface Contracts

### New events consumed
- `CapacitySnapshotUpdated` from `events:capacity-availability` — fields: `segmentRef`, `departureDate`, `totalCapacity`, `remainingCapacity`, `snapshotVersion`

### Events produced (unchanged)
- `FareQuoteComputed` — existing event, but `breakdown` now includes richer components:
  - `baseDistanceFare` or `baseFlat` (which base fare method)
  - `seatClassMultiplier`
  - `advancePurchaseTier` with `tierName` and `multiplier`
  - `peakAdjustment` with `dateAdjustment` and `hourAdjustment`
  - `dynamicCapacityAdjustment` with `remainingPct` and `adjustmentPct`

### API changes
- `POST /api/v1/fare-quotes` request body adds optional fields:
  - `seatClass: string` (default: "SECOND_CLASS")
  - `distanceKm: number` (optional, for distance-based pricing)
  - `departureTime: string` (ISO datetime, for peak pricing)
- Response `breakdown` object gains the new component fields

## Test Criteria

1. A quote for departure 25 days out, second class, off-peak → base fare with 30% advance discount and 10% off-peak discount
2. A quote for departure tomorrow, peak hour, first class → base × 1.6 × 1.20 surge × 1.15 peak hour
3. A quote with 8% remaining capacity → additional 50% dynamic surcharge on top
4. Idempotency: same input hash → same quote ID
5. All price components visible in `breakdown` and `explanation`

## Files to Modify

- `services/fare-pricing/src/fare_pricing/domain.py` — new dataclasses, enriched calculation
- `services/fare-pricing/src/fare_pricing/service.py` — wire new parameters
- `services/fare-pricing/src/fare_pricing/handlers.py` — consume capacity events
- `services/fare-pricing/src/fare_pricing/api.py` — accept new request fields
- `services/fare-pricing/src/fare_pricing/schemas.py` — request/response schema updates
- `services/fare-pricing/migrations/002_pricing_enrichment.sql` — schema for capacity cache table
- `services/fare-pricing/tests/test_domain.py` — unit tests for all tier/peak/dynamic calculations
