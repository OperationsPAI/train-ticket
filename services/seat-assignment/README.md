# Seat Assignment

Go implementation of CRH380A train layout modelling, preference-based seat assignment, inventory holds, confirmation, release lifecycle, HTTP API, Postgres persistence, and Redis event integration.

## Supply facts and standing quota

`GET /api/v1/internal/capacity-facts` exposes a published composition and active allocation references for a scheduled service, date and class.
The query is read only and includes historical allocations.
Standing has no concrete SeatUnit; its versioned admission quota is ten percent of assignable second class seats, rounded down.
The quota is checked under the service allocation lock and recorded on allocations and events.
Allocation replay validates its class against the composition recorded on the original allocation.
A later composition retirement or replacement does not change an existing allocation's identity or seat reference.

See [Standing capacity policy](../../docs/08-contracts/standing-capacity-policy.md) and [HTTP API](../../docs/08-contracts/api/seat-assignment.md).

## Verification

Run `go test ./...` from this directory.
Set `TEST_DATABASE_URL`, `TEST_CAPACITY_SEAT_FACTS` and `TEST_STANDING_SOURCE` to run the recorded PostgreSQL supply and standing tests.
The tests create isolated schemas and remove those schemas after verification.
