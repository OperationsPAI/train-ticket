# Trip Planning — HTTP API

Last updated: 2026-07-05

## Overview

Trip Planning searches for itineraries based on a traveler's intent. It
returns candidate itineraries with non-authoritative price and availability
hints. It does not lock inventory or create offers.

## Endpoints

### Search Itineraries

**POST** `/api/v1/itineraries/search`

**Idempotency:** NOT REQUIRED (query endpoint; idempotency key is ignored if present)

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `originRef` | string | yes | Origin place or node reference. |
| `destinationRef` | string | yes | Destination place or node reference. |
| `departureDate` | string | yes | Departure date (ISO-8601 date, e.g. `"2026-07-10"`). |
| `returnDate` | string | no | Return date for round trips. |
| `travelerRefs` | string[] | yes | Traveler references for pricing hints. |
| `channel` | string | yes | Sales channel. |
| `maxResults` | integer | no | Maximum results to return (default 10, max 50). |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `intentRef` | string | Hash/fingerprint of the original request. |
| `itineraries` | array | Ordered list of candidate itineraries. |
| `planningSnapshotRefs` | string[] | References to upstream snapshots used. |

**Itinerary:**

| Field | Type | Description |
|---|---|---|
| `itineraryRef` | string | Stable itinerary reference (`itin_<sha256>`). |
| `legs` | array | Ordered legs comprising the itinerary. |
| `priceHint` | object | Non-authoritative price estimate. |
| `availabilityHint` | object | Non-authoritative availability estimate. |

**LegCandidate:**

| Field | Type | Description |
|---|---|---|
| `servicePlanRef` | string | Reference to the service plan. |
| `serviceSegmentRef` | string | Reference to the service segment. |
| `originStopRef` | string | Origin stop reference. |
| `destinationStopRef` | string | Destination stop reference. |
| `departureTime` | timestamp | Scheduled departure. |
| `arrivalTime` | timestamp | Scheduled arrival. |
| `mode` | string | Transport mode (default: `train`). |

**Error codes:** `VALIDATION_FAILED`, `UNAVAILABLE`

### Get Itinerary

**GET** `/api/v1/itineraries/{itineraryRef}`

**Response (200):** Full itinerary details.

**Error codes:** `NOT_FOUND`

## Open Issues

- None.
