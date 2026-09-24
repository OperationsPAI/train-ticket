# Service Plan seasonal schedules and operational facts

Last updated: 2026-09-24.

## Runtime and ownership

Service Plan is implemented in Go under `services/service-plan`.
Its domain model is in `internal/domain/service_plan.go`, application operations are in `internal/application/service.go`, and HTTP routes are in `internal/http/router.go`.
The [domain document](../02-domains/service-plan.md) defines its current boundary.
Remaining work is tracked in [docs/TODO.md](../TODO.md).

Service Plan records timetable and service availability facts.
It does not allocate seats, admit Capacity Holds, price tickets or perform disruption compensation.
The current HTTP creation path builds a scheduled service with exactly two stops.
The internal segment supply endpoint reports their zero based interval as `[0,1)`.

## Seasonal periods

The domain selects an active period for a date and exposes its multiplier as schedule metadata.

| Period | Inclusive dates | Multiplier |
|---|---|---|
| `REGULAR` | January 1 through December 31 | 1.00 |
| `SPRING_RUSH` | January 10 through March 10 | 1.30 |
| `SUMMER_RUSH` | July 1 through August 31 | 1.20 |
| `NATIONAL_DAY` | September 28 through October 8 | 1.25 |
| `LABOR_DAY` | April 29 through May 5 | 1.15 |

These are the default simulation periods generated for the plan year.
The scheduled service view includes `schedulePeriod` and `capacityMultiplier`.
No Capacity calculation in the combined supply path multiplies physical units by these values.
Concrete capacity comes from Seat Assignment's published composition.
The published standing policy separately derives a quota of ten percent of assignable second class seats.

## Temporary services

`POST /api/v1/scheduled-services/{serviceRef}/temporary-services` accepts a temporary service reference, an `L` train number, period, stop subset and available classes.
The application validates and stores the record in the base service's `temporaryServices` and publishes `TemporaryServiceAdded`.
The domain's `ActiveVariant` selects unexpired temporary records for the active period.

This operation does not materialize an independent scheduled service or create a searchable segment.
The current Trip Planning Rust event filter does not consume `TemporaryServiceAdded`.
A stored temporary record therefore does not establish that an extra train is visible in search or available for booking.

The domain and application implement `ExpireTemporaryServices`.
An explicit call after the period end records expiry and publishes `TemporaryServiceExpired`.
The production process entry point does not schedule this operation, and no expiry HTTP route is registered.
Automatic cleanup cannot be inferred from the method's existence.

## Delay facts

`POST /api/v1/scheduled-services/{serviceRef}/delays` accepts `segmentRef` and `delayMinutes`.
The domain records planned and estimated times and uses two minutes of recovery per downstream stop, with zero as the minimum delay.
The application persists delay records and publishes `TrainDelayed` with service, segment, delay minutes and estimated departure.

The generic propagation algorithm supports ordered domain segments.
The HTTP service still creates only two stops, so a multi stop domain example does not establish integrated multi stop timetable creation.
Delay records do not overwrite the persisted segment's original planned departure and arrival fields.
Trip Planning's current index does not consume `TrainDelayed`, so its stored search timetable does not automatically reflect this operation.

## Dated cancellation and restoration

`POST /api/v1/scheduled-services/{serviceRef}/cancellations` records a date and reason and publishes `TrainCancelled`.
When the date matches the instance departure date, the service view becomes `CANCELLED` with `bookable=false`.
`POST /api/v1/scheduled-services/{serviceRef}/restorations` restores an active cancellation and publishes `TrainRestored`.
For the instance departure date, the view becomes `ACTIVE` with `bookable=true`.

These operations persist planning facts.
They do not themselves release inventory, alter existing tickets or notify every affected passenger.
The current Trip Planning event filter has no cancellation or restoration handler.
A successful cancellation response cannot establish that the train has disappeared from search.

## Persistence and evidence

The production service uses `scheduled_service_snapshots`, `service_segment_snapshots`, PostgreSQL idempotency storage and an outbox relay.
Temporary services, cancellations and delay records are stored with the scheduled service.
Repository writes and event appends share the application UnitOfWork.
Service reconstruction restores these operational records.

`GET /api/v1/service-segments` supports pagination by scheduled service.
The internal `GET /api/v1/internal/service-segments/{segmentRef}/supply` validates endpoints and timestamps against that service and returns its date, ordered stops and stop sequence digest.
Capacity's consumer of these supply facts is committed as `caafa668` and undeployed; Booking coordinated behavior is not enabled.

Existing domain, application and HTTP tests cover their corresponding operations.
`internal/http/segment_live_test.go` uses recorded service facts with PostgreSQL to exercise the segment reads and supply response.
Local domain tests and existing records are not evidence that every downstream schedule effect is active.
No new tests or deployment checks were run for this documentation update.
