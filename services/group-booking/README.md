# Group Booking

Java/Spring foundation for the Group Booking bounded context. It owns group reservations for 10+ travelers, group fare negotiation references, roster membership, capacity hold confirmation acknowledgement, confirmation, and cancellation/partial cancellation decisions.

## API

- `GET /health`
- `POST /api/v1/group-bookings`
- `PUT /api/v1/group-bookings/{groupBookingId}/members`
- `POST /api/v1/group-bookings/{groupBookingId}/confirm`
- `POST /api/v1/group-bookings/{groupBookingId}/cancel`
- `GET /api/v1/group-bookings/{groupBookingId}`

The domain stores only traveler references and masked document references; raw identity documents must not enter this service.

When `DATABASE_URL` is configured, the owning API persists group bookings and roster members in PostgreSQL, writes domain events to the transactional outbox, and stores HTTP idempotency responses in the same database. A process restart therefore preserves `POST /api/v1/group-bookings`, roster updates and later confirmation through `GET`/`POST` owning endpoints. The in-memory repository is used only when no database is configured.
