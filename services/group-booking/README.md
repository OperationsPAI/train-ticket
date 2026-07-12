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
