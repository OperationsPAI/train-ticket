# Transfer Management Enrichment — Connection Constraints & Auto-Rebooking

## Context

**Service**: transfer-management (Go, `services/transfer-management/`)
**Current state**: 612-line domain, basic transfer creation. No minimum connection time, no missed-connection handling, no cross-mode support.

## Requirements

### R1: Minimum Connection Time Enforcement

```
Per-station minimum connection time (MCT):
  LARGE_HUB (北京南, 上海虹桥, 广州南):  25 minutes
  MEDIUM_HUB (南京南, 武汉, 成都东):     20 minutes
  SMALL_STATION:                        15 minutes
  CROSS_MODE (高铁→地铁):                35 minutes
  SAME_PLATFORM:                        10 minutes

Validation:
  arrival_time + MCT <= departure_time of next leg
  If violated → REJECT transfer with reason INSUFFICIENT_CONNECTION_TIME
```

**Domain model**:
- `MinimumConnectionTime` struct: `stationRef`, `fromMode`, `toMode`, `minutes`
- `ConnectionValidator`: checks MCT between consecutive legs
- `TransferProposal` aggregate gains `validateConnectionTimes()` method

### R2: Missed Connection Auto-Rebooking

```
When a train is delayed causing a missed connection:
  1. Detect: actual_arrival > next_departure - MCT
  2. Search alternative connections from the transfer station
  3. If alternative found within 4 hours:
     → Auto-rebook to next available
     → Notify passenger
     → No additional charge (carrier responsibility)
  4. If no alternative within 4 hours:
     → Offer refund for remaining legs
     → Arrange alternative transport if available
```

**Domain model**:
- `MissedConnectionDetector`: consumes delay events, checks against booked connections
- `RebookingRequest`: `originalTransferId`, `missedLegRef`, `searchWindow`
- `RebookingSuggestion`: `newSegmentRef`, `newDepartureTime`, `additionalCost` (should be 0 for carrier fault)
- Events: `ConnectionMissed`, `AutoRebookingCompleted`, `RebookingFailed`

### R3: Cross-Mode Transfer Support

```
Supported transfer modes:
  TRAIN → TRAIN:     standard (same station or nearby stations)
  TRAIN → METRO:     requires station walking time
  TRAIN → BUS:       requires transfer to bus terminal
  FLIGHT → TRAIN:    airport to train station (separate MCT)

Each mode pair has:
  - MCT override
  - Transfer instructions (text template)
  - Walking distance estimate
```

**Domain model**:
- `TransferMode` enum: TRAIN, METRO, BUS, FLIGHT, TAXI
- `CrossModeTransfer`: `fromMode`, `toMode`, `fromStationRef`, `toStationRef`, `walkingMinutes`, `mctOverride`

### R4: Connection Guarantee Policy

```
Guaranteed connections (联程票保障):
  - If booked as a single itinerary (not separate tickets):
    → Carrier guarantees the connection
    → Missed connection = involuntary rebooking at no cost
    → If no rebooking possible = full refund of remaining legs

Non-guaranteed connections:
  - Separate tickets = passenger's risk
  - Missed connection = voluntary change rules apply
```

**Domain model**:
- `ConnectionGuarantee`: `transferId`, `guaranteed: boolean`, `itineraryRef`
- Guaranteed flag set based on whether legs are from same itinerary purchase

## Events Consumed
- `events:disruption-recovery` → `TrainDelayed`, `TrainCancelled`
- `events:trip-planning` → `ItineraryProposed` (for MCT validation)

## Events Produced
- `ConnectionValidated` — MCT check passed
- `ConnectionMissed` — delay caused missed connection
- `AutoRebookingCompleted` — passenger rebooked to alternative
- `RebookingFailed` — no alternative found

## Test Criteria

1. Transfer with 10min gap at large hub (MCT=25min) → REJECT
2. Transfer with 30min gap at large hub → ACCEPT
3. Train delay causing arrival after next departure → ConnectionMissed event
4. Auto-rebooking finds alternative within 4h → passenger rebooked, no charge
5. Guaranteed connection missed → involuntary refund for remaining legs

## Files to Modify

- `services/transfer-management/internal/domain/` — MCT, rebooking, cross-mode
- `services/transfer-management/internal/application/`
- `services/transfer-management/internal/adapters/messaging/` — consume delay events
- `services/transfer-management/migrations/`
