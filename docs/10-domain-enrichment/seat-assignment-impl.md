# Seat Assignment — Full Implementation

## Context

**Service**: seat-assignment (Go, `services/seat-assignment/`)
**Current state**: Empty shell — 0 lines of implementation. Only Dockerfile and module config exist. This service needs to be built from scratch following the Go-kit patterns.

**Platform kit**: `platform/go-kit/` provides storage, messaging, HTTP, and outbox primitives.
**DDD spec**: `docs/02-domains/seat-assignment.md`

## Requirements

### R1: Train Car Layout Model

Model the physical layout of train cars:

```
TrainConfiguration:
  trainType: "CRH380A"
  cars:
    - carNumber: 1
      carType: BUSINESS_CLASS
      layout: "2+1"          # seats per row
      rows: 12
      totalSeats: 36
      features: [POWER_OUTLET, WIFI, RECLINER]
    - carNumber: 2-5
      carType: FIRST_CLASS
      layout: "2+2"
      rows: 20
      totalSeats: 80 per car
      features: [POWER_OUTLET]
    - carNumber: 6-13
      carType: SECOND_CLASS
      layout: "3+2"
      rows: 25
      totalSeats: 125 per car
      features: []
    - carNumber: 14-16
      carType: SECOND_CLASS
      layout: "3+2"
      rows: 25
      totalSeats: 125 per car
      features: [QUIET_CAR]  # car 16 only

Seat identifiers: "{carNumber}-{row}{letter}" e.g. "06-12A"
Letters: A,B,C (aisle) D,E for 3+2; A,B (aisle) C,D for 2+2; A (aisle) B,C for 2+1
```

**Domain model** (`internal/domain/`):
- `TrainConfig` struct: `TrainType string`, `Cars []CarConfig`
- `CarConfig` struct: `CarNumber int`, `CarType string`, `Layout string`, `Rows int`, `Features []string`
- `Seat` struct: `SeatId string`, `CarNumber int`, `Row int`, `Letter string`, `SeatType string`, `Position string` (WINDOW, AISLE, MIDDLE)
- `SeatMap` struct: ordered collection of all seats for a train config

### R2: Seat Assignment Engine

Assign seats based on preferences:

```
Preferences (priority order):
  1. WINDOW — seat letters A or E (3+2), A or D (2+2)
  2. AISLE — seat letters C or D (3+2), B or C (2+2)
  3. TOGETHER — group of travelers should be adjacent
  4. QUIET_CAR — assign to car 16 (quiet zone)
  5. FORWARD_FACING — odd rows (platform-specific)
  6. NO_PREFERENCE — any available seat

Assignment algorithm:
  1. Filter available seats matching seat class
  2. If TOGETHER requested: find consecutive seats in same row
  3. Score remaining seats by preference match (0-100)
  4. Assign highest-scoring available seat
  5. If no preference match: fall back to any available
```

**Domain model**:
- `SeatPreference` struct: `PreferenceType string`, `Priority int`
- `AssignmentRequest` struct: `SegmentRef string`, `TravelerRefs []string`, `SeatClass string`, `Preferences []SeatPreference`
- `SeatAssignment` aggregate: `AssignmentId string`, `SegmentRef string`, `TravelerRef string`, `SeatId string`, `AssignedAt time.Time`, `Status string` (ASSIGNED, CONFIRMED, RELEASED)
- `AssignmentEngine`: pure function `Assign(seatMap SeatMap, occupied []string, requests []AssignmentRequest) → []SeatAssignment`

### R3: Seat Availability Tracking

Track which seats are occupied per segment+date:

```
SeatInventory:
  segmentRef: "seg-xxx"
  departureDate: "2026-07-20"
  trainConfig: "CRH380A"
  assignments: map[seatId] → { travelerRef, holdId, status }
  
Status lifecycle:
  AVAILABLE → HELD (temporary, during booking) → CONFIRMED (ticket issued) → RELEASED (cancellation)
  HELD expires after 10 minutes if not confirmed
```

**Domain model**:
- `SeatInventory` aggregate root: `SegmentRef`, `DepartureDate`, `TrainConfigRef`, `Assignments map[string]*SeatAssignment`
- `HoldSeat(seatId, travelerRef, holdId) → error` — marks seat as HELD
- `ConfirmSeat(seatId, holdId) → error` — transitions HELD → CONFIRMED
- `ReleaseSeat(seatId) → error` — transitions HELD/CONFIRMED → AVAILABLE
- `ExpireHolds(olderThan time.Time)` — bulk release expired holds

### R4: API Endpoints

```
POST /api/v1/seat-assignments
  Request: { segmentRef, departureDate, travelerRefs, seatClass, preferences }
  Response: { assignments: [{ assignmentId, seatId, travelerRef, carNumber, row, letter, position }] }

GET /api/v1/seat-assignments/{segmentRef}/{departureDate}/availability
  Response: { totalSeats, available, occupied, byClass: { SECOND_CLASS: { total, available }, ... } }

DELETE /api/v1/seat-assignments/{assignmentId}
  Response: { released: true }

POST /api/v1/seat-assignments/{assignmentId}/confirm
  Response: { confirmed: true, seatId, travelerRef }
```

## Interface Contracts

### Events consumed
- `events:booking-orchestration` → `BookingSagaStepSucceeded` (capacity hold confirmed) — trigger seat assignment
- `events:entitlement-ticketing` → `TicketIssued` — confirm seat assignment
- `events:post-sales` → `PostSalesApplied` (refund) — release seat

### Events produced
- `SeatAssigned` — assignment created
- `SeatConfirmed` — assignment confirmed
- `SeatReleased` — seat freed
- `SeatAssignmentFailed` — no matching seat available

## Test Criteria

1. Assign window seat preference → gets seat A or E in 3+2 layout
2. Assign 3 travelers TOGETHER → all in same row, adjacent
3. Assign to full car → correctly falls back to next car
4. Hold expires after 10 minutes → seat becomes available
5. Confirm after hold → status transitions correctly
6. Release after confirm (refund) → seat available for rebooking

## Files to Create

- `services/seat-assignment/internal/domain/train_config.go`
- `services/seat-assignment/internal/domain/seat.go`
- `services/seat-assignment/internal/domain/assignment.go`
- `services/seat-assignment/internal/domain/inventory.go`
- `services/seat-assignment/internal/domain/engine.go`
- `services/seat-assignment/internal/application/service.go`
- `services/seat-assignment/internal/adapters/api/handlers.go`
- `services/seat-assignment/internal/adapters/storage/postgres.go`
- `services/seat-assignment/internal/adapters/messaging/subscriber.go`
- `services/seat-assignment/migrations/001_seat_assignment.sql`
- `services/seat-assignment/cmd/main.go` (update existing)
- `services/seat-assignment/internal/domain/engine_test.go`
