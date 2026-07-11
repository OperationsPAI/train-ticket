# Disruption Recovery Enrichment — Auto-Rerouting & Compensation

## Context

**Service**: disruption-recovery (Python, `services/disruption-recovery/`)
**Current state**: 391-line domain, basic disruption declaration. No rerouting algorithm, no compensation policy, no batch processing for mass disruptions.

## Requirements

### R1: Disruption Declaration & Classification

```
Disruption types:
  DELAY:        train delayed (with estimated new time)
  CANCELLATION: train cancelled entirely
  PARTIAL:      some stops skipped
  FORCE_MAJEURE: weather, natural disaster, infrastructure failure

Severity levels:
  MINOR:   delay < 30 min (no compensation)
  MODERATE: delay 30-120 min (optional rebooking)
  SEVERE:  delay > 120 min (automatic compensation)
  CRITICAL: cancellation or force majeure (full refund + rebooking)
```

**Domain model**:
- `Disruption` aggregate: `disruptionId`, `segmentRef`, `type`, `severity`, `declaredAt`, `estimatedResolution`, `affectedPassengerCount`
- `DisruptionClassifier`: (type, delayMinutes) → severity
- `AffectedBooking`: `orderId`, `travelerId`, `segmentRef`, `compensationStatus`

### R2: Automatic Rerouting Algorithm

```
When a train is cancelled or severely delayed:
  1. Find all affected bookings for that segment+date
  2. For each affected booking:
     a. Search alternative routes (same O-D, within ±4 hours)
     b. Score alternatives by: time deviation, seat class match, number of transfers
     c. If top alternative score > threshold → auto-rebook
     d. If no good alternative → offer refund

Scoring:
  time_deviation: 100 - (abs(original_departure - new_departure) in minutes) / 2
  class_match:    same class = 30, downgrade = 15, upgrade = 25
  transfers:      direct = 20, 1 transfer = 10, 2+ = 0
  total = time_deviation + class_match + transfers (max 150)
  auto_rebook_threshold = 80
```

**Domain model**:
- `ReroutingEngine`: finds and scores alternatives
- `ReroutingSuggestion`: `newSegmentRef`, `score`, `departureTime`, `seatClass`, `transfers`
- `ReroutingDecision`: `AUTO_REBOOK`, `OFFER_REFUND`, `MANUAL_REVIEW`

### R3: Compensation Policy Engine

```
Compensation rules (modeled after EU rail passenger rights):
  Delay 30-59 min:   no compensation
  Delay 60-119 min:  25% of ticket price
  Delay ≥ 120 min:   50% of ticket price
  Cancellation:      full refund + 25% compensation
  Force majeure:     full refund only (no extra compensation)

Compensation delivery:
  - Credited as points (default) OR
  - Cash refund to original payment channel (on request)
  - Issued within 7 days of disruption resolution
```

**Domain model**:
- `CompensationPolicy`: `delaySeverity`, `compensationPct`, `deliveryMethod`
- `CompensationCalculator`: (ticketPrice, delayMinutes, disruptionType) → compensationAmount
- `CompensationClaim`: `claimId`, `orderId`, `amount`, `status` (PENDING, APPROVED, PAID, REJECTED)

### R4: Mass Disruption Batch Processing

```
When an entire train is cancelled:
  - Potentially 1000+ affected bookings
  - Must process in batches (100 per batch)
  - Priority: business class → first class → second class
  - Each batch: find alternatives → auto-rebook or refund
  - Progress tracking: X/Y passengers processed
  - SLA: all passengers processed within 30 minutes of declaration

Event flow:
  DisruptionDeclared → MassDisruptionDetected → BatchProcessingStarted
  → per batch: AlternativesSearched → RebookingDecided
  → BatchProcessingCompleted → CompensationIssued
```

**Domain model**:
- `MassDisruptionProcessor`: orchestrates batch processing
- `ProcessingBatch`: `batchId`, `bookings[]`, `status`, `processedCount`
- `MassDisruptionProgress`: `totalAffected`, `processed`, `rebooked`, `refunded`, `pending`

## Events Consumed
- `events:service-plan` → `TrainCancelled`, `TrainDelayed`
- `events:journey-order` → affected bookings lookup
- `events:capacity-availability` → alternative capacity

## Events Produced
- `DisruptionDeclared` — disruptionId, segmentRef, type, severity
- `MassDisruptionDetected` — disruptionId, affectedCount
- `PassengerRebooked` — orderId, newSegmentRef
- `CompensationIssued` — orderId, amount, method
- `DisruptionResolved` — disruptionId, totalProcessed

## Test Criteria

1. 90-minute delay → 25% compensation calculated
2. Train cancellation → all passengers found, batch processing starts
3. Auto-rerouting: cancelled train, alternative 2h later same class → score > 80, auto-rebook
4. No alternative within 4h → offer refund
5. Force majeure cancellation → full refund, no extra compensation
6. 500 affected passengers → processed in < 5 batches

## Files to Modify

- `services/disruption-recovery/src/disruption_recovery/domain.py`
- `services/disruption-recovery/src/disruption_recovery/application/`
- `services/disruption-recovery/src/disruption_recovery/api.py`
- `migrations/`
