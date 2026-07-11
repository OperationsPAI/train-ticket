# Waitlist — Full Implementation

## Context

**Service**: waitlist (TypeScript, `services/waitlist/`)
**Current state**: Empty shell — 0 lines of domain logic. Has basic runtime setup using ts-kit. Needs full implementation of priority-based waiting queue and auto-promotion.

**Platform kit**: `platform/ts-kit/` provides storage, messaging, HTTP, and outbox primitives.
**DDD spec**: `docs/02-domains/waitlist.md`

## Requirements

### R1: Waitlist Entry Model

When a segment is sold out, passengers can join a waitlist:

```
WaitlistEntry:
  entryId: "wl-xxx"
  accountId: "acc-xxx"
  travelerRefs: ["tvl-xxx"]
  segmentRef: "seg-xxx"
  departureDate: "2026-07-20"
  seatClass: "SECOND_CLASS"
  priorityScore: 85          # computed from multiple factors
  status: QUEUED | OFFERED | ACCEPTED | EXPIRED | CANCELLED
  queuePosition: 3           # rank within the waitlist
  createdAt: ISO datetime
  offeredAt: ISO datetime | null
  offerExpiresAt: ISO datetime | null
```

**Domain model** (`src/domain.ts`):
- `WaitlistEntry` class with status lifecycle
- `WaitlistQueue` aggregate: ordered collection of entries for a segment+date+class
- Queue is maintained sorted by `priorityScore` DESC, then `createdAt` ASC

### R2: Priority Scoring

Compute a 0-100 priority score based on:

```
Factor weights:
  loyalty_tier:     0-30 points
    PLATINUM: 30, GOLD: 20, SILVER: 10, NONE: 0
  
  booking_history:  0-20 points
    > 20 trips in past year: 20
    10-20 trips: 15
    5-9 trips: 10
    < 5 trips: 5
  
  advance_booking:  0-15 points
    > 14 days before departure: 15
    7-14 days: 10
    < 7 days: 5
  
  group_size:       0-10 points
    single traveler: 10 (easier to seat)
    2 travelers: 8
    3-4: 5
    5+: 2
  
  fare_class:       0-15 points
    BUSINESS: 15, FIRST: 12, SECOND: 8, STANDING: 3
  
  special:          0-10 points
    military/disabled: +10
    student: +5
```

**Domain model**:
- `PriorityCalculator`: pure function taking `PriorityInput` → `score: number`
- `PriorityInput`: `loyaltyTier`, `tripCount`, `daysBefore`, `groupSize`, `fareClass`, `specialStatus`

### R3: Auto-Promotion on Cancellation

When capacity-availability emits `WaitlistCapacityFreed`:

```
Promotion flow:
  1. Receive WaitlistCapacityFreed event with segmentRef, departureDate, freedSlots
  2. For each freed slot:
     a. Pick highest-priority QUEUED entry
     b. Request a fare quote from fare-pricing
     c. Request a capacity hold from capacity-availability
     d. If both succeed: transition entry to OFFERED, send notification
     e. Set offer expiry (15 minutes)
  3. If passenger accepts within expiry:
     a. Create journey order
     b. Transition to ACCEPTED
  4. If offer expires:
     a. Release capacity hold
     b. Transition to EXPIRED
     c. Promote next in queue
```

**Domain model**:
- `PromotionOrchestrator`: coordinates the promotion saga
- `WaitlistOffer`: `offerId`, `entryId`, `fareQuoteId`, `capacityHoldId`, `expiresAt`
- Events: `WaitlistEntryPromoted`, `WaitlistOfferExpired`, `WaitlistEntryAccepted`

### R4: API Endpoints

```
POST /api/v1/waitlist/entries
  Request: { accountId, travelerRefs, segmentRef, departureDate, seatClass }
  Response: { entryId, queuePosition, priorityScore, estimatedWaitMinutes }

GET /api/v1/waitlist/entries/{entryId}
  Response: { entry details, current position, status }

DELETE /api/v1/waitlist/entries/{entryId}
  Response: { cancelled: true }

POST /api/v1/waitlist/entries/{entryId}/accept
  Request: { paymentMethodRef }
  Response: { orderId, seatAssignment }

GET /api/v1/waitlist/segments/{segmentRef}/{departureDate}/queue
  Response: { totalQueued, myPosition (if authenticated), estimatedPromotionRate }
```

## Interface Contracts

### Events consumed
| Stream | Event Type | Purpose |
|--------|-----------|---------|
| `events:capacity-availability` | `WaitlistCapacityFreed` | Trigger auto-promotion |
| `events:journey-order` | `JourneyOrderCancelled` | Detect cancellation (may trigger capacity freed) |
| `events:loyalty-membership` | `MemberTierChanged` | Update priority score |

### Events produced
| Event Type | Consumers |
|-----------|-----------|
| `WaitlistEntryCreated` | notification, reporting |
| `WaitlistEntryPromoted` | notification (send offer to passenger) |
| `WaitlistOfferExpired` | capacity-availability (release hold) |
| `WaitlistEntryAccepted` | journey-order, payment |

### Downstream calls (HTTP)
- `POST fare-pricing/api/v1/fare-quotes` — get current price for promoted entry
- `POST capacity-availability/api/v1/capacity/holds` — request hold for promoted entry  
- `POST journey-order/api/v1/journey-orders` — create order when accepted

## Test Criteria

1. Joining waitlist for sold-out segment → entry created with correct priority score
2. Platinum member scores higher than non-member → correct queue ordering
3. WaitlistCapacityFreed triggers promotion of highest-priority entry
4. Promoted entry not accepted within 15 min → offer expires, next promoted
5. Accept promotion → journey order created
6. Cancel waitlist entry → removed from queue, positions updated

## Files to Create

- `services/waitlist/src/domain.ts` — WaitlistEntry, WaitlistQueue, PriorityCalculator
- `services/waitlist/src/promotion.ts` — PromotionOrchestrator, WaitlistOffer
- `services/waitlist/src/app.ts` — HTTP handlers
- `services/waitlist/src/subscriber.ts` — event consumption
- `services/waitlist/src/publisher.ts` — event publishing
- `services/waitlist/migrations/001_waitlist.sql` — schema
- `services/waitlist/tests/domain.test.ts` — priority scoring tests
- `services/waitlist/tests/promotion.test.ts` — promotion flow tests
