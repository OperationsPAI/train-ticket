# Group Booking — HTTP API

Last updated: 2026-09-25.

Group Booking owns group reservations for at least ten travelers. All POST commands require a UUID-v7 `Idempotency-Key`; persisted runtime stores both aggregate state and successful HTTP responses in PostgreSQL.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/group-bookings` | Create a draft group booking. |
| GET | `/api/v1/group-bookings/{groupBookingId}` | Read the owning aggregate and roster. |
| PUT | `/api/v1/group-bookings/{groupBookingId}/members` | Add active members using traveler and masked-document references. |
| POST | `/api/v1/group-bookings/{groupBookingId}/confirm` | Confirm a complete roster against a capacity hold. |
| POST | `/api/v1/group-bookings/{groupBookingId}/cancel` | Cancel the draft/hold or selected members according to group-size rules. |

Create requires `organizerRef`, nonempty `segmentRefs`, `targetTravelerCount >= 10`, and `fare` containing currency, minor units, discount basis points and optional negotiation reference. Confirmation requires at least ten active members and a `capacityHoldId`.

With PostgreSQL configured, create/update state and emitted outbox events commit atomically. A restarted instance must return the same aggregate through the GET endpoint and may continue its lifecycle. Replaying a successful POST with the same key and body returns the stored response and does not create another group or event.
