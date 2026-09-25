# Group Booking Events

Last updated: 2026-09-25.

Group Booking publishes to `events:group-booking` through a transactional outbox when PostgreSQL is configured.

| Event | Required business fields |
|---|---|
| `GroupBookingCreated` | `groupBookingId`, `organizerRef`, `segmentRefs`, `targetTravelerCount`, `fare`, `discountBasisPoints`, `negotiationRef` |
| `GroupMemberAdded` | `groupBookingId`, `memberId`, `travelerRef` |
| `GroupBookingConfirmed` | `groupBookingId`, `capacityHoldId`, `confirmedTravelerCount`, `memberIds` |
| `GroupBookingCancelled` | `groupBookingId`, `reason`, `cancelledMemberIds` |

The service consumes `CapacityHoldConfirmed` from `events:capacity-availability`. It accepts `groupBookingId` or `businessRef`, `holdId` or `capacityHoldId`, and a confirmed quantity. A valid fact records the hold on the owning group aggregate without publishing a second confirmation event; the explicit confirm API publishes `GroupBookingConfirmed` after roster validation.

All event envelopes use canonical UUID-v7 event, correlation and causation identifiers. The roster payload never contains raw identity documents.
