# Service Plan Enrichment — Seasonal Schedules & Real-Time Delays

## Context

**Service**: service-plan (Rust, `services/service-plan/`)
**Current state**: 893-line domain, basic service plan CRUD with segment creation. No seasonal variations, no temporary train additions, no delay propagation.

## Requirements

### R1: Seasonal Schedule Variants

```
Schedule periods:
  REGULAR:      standard timetable (most of the year)
  SPRING_RUSH:  春运 (Jan 10 - Mar 10), +30% capacity, temporary trains added
  SUMMER_RUSH:  暑运 (Jul 1 - Aug 31), +20% capacity
  NATIONAL_DAY: 国庆 (Sep 28 - Oct 8), +25% capacity
  LABOR_DAY:    五一 (Apr 29 - May 5), +15% capacity

Temporary trains (临客):
  - Added during rush periods
  - Same route as regular trains but different train number (prefix 'L')
  - May have fewer stops
  - Capacity often standing-only or second-class only
```

**Domain model**:
- `SchedulePeriod`: `periodType`, `startDate`, `endDate`, `capacityMultiplier`
- `TemporaryService`: `baseServiceRef`, `tempTrainNumber`, `periodRef`, `stopsSubset`, `availableClasses`
- `ServicePlan` aggregate gains `activeVariant(date) → ScheduleVariant` method
- Events: `TemporaryServiceAdded`, `TemporaryServiceRemoved`, `SchedulePeriodActivated`

### R2: Real-Time Delay Propagation

```
Delay model:
  - Each segment has `scheduledDeparture` and `actualDeparture` (initially null)
  - Delay at station N propagates to stations N+1, N+2, ... with dampening
  - Dampening: each subsequent stop reduces delay by 2 minutes (crew recovery)
  - Minimum propagated delay: 0 minutes

Example:
  Train G1 stops: Beijing → Jinan → Nanjing → Shanghai
  Delay at Beijing: 30 minutes
  Propagated: Jinan +28min, Nanjing +26min, Shanghai +24min

Event:
  TrainDelayed: { serviceRef, segmentRef, delayMinutes, estimatedNewDeparture }
  Published for each affected segment
```

**Domain model**:
- `DelayRecord`: `segmentRef`, `scheduledTime`, `estimatedTime`, `delayMinutes`, `source`
- `DelayPropagator`: given delay at one stop, compute downstream delays
- `ServicePlan` gains `recordDelay(segmentRef, delayMinutes)` → list of propagated `TrainDelayed` events

### R3: Temporary Train Cancellation & Restoration

```
Operations:
  - Cancel a specific service for a specific date (not permanently)
  - Restore a previously cancelled service
  - Cancel all temporary services when rush period ends

Events:
  TrainCancelled: { serviceRef, date, reason }
  TrainRestored:  { serviceRef, date }
  TemporaryServiceExpired: { serviceRef, periodEndDate }
```

**Domain model**:
- `ServiceCancellation`: `serviceRef`, `date`, `reason`, `cancelledAt`, `restoredAt`
- `ServicePlan` gains `cancelForDate(date, reason)` and `restoreForDate(date)`

## Events Produced
- `ScheduledServiceCreated` (existing)
- `TemporaryServiceAdded` — tempServiceRef, baseServiceRef, period
- `TrainDelayed` — serviceRef, segmentRef, delayMinutes
- `TrainCancelled` — serviceRef, date, reason
- `TrainRestored` — serviceRef, date

## Consumers
- `trip-planning` — adjusts search results based on delays/cancellations
- `disruption-recovery` — triggers rerouting on cancellation/severe delay
- `notification` — sends delay alerts to affected passengers

## Test Criteria

1. During SPRING_RUSH period → temporary trains visible in schedule
2. 30min delay at origin → propagated with dampening to all downstream stops
3. Train cancelled for specific date → not bookable, existing bookings flagged
4. Rush period ends → temporary services auto-expired

## Files to Modify

- `services/service-plan/src/domain.rs` — seasonal, delay propagation
- `services/service-plan/src/api.rs` — delay recording API
- `services/service-plan/src/application.rs`
- `migrations/`
