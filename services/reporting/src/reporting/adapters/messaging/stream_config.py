from __future__ import annotations

PRODUCER_CONTEXT = "reporting"
CONSUMER_GROUP = "reporting"
STREAM_PREFIX = "events:"
STREAM_MAXLEN = 100_000
RETRY_LIMIT = 5
MIN_IDLE_MS = 60_000
BLOCK_MS = 2_000
READ_COUNT = 10

# The streams that carry a fact this service actually projects, and nothing else.
#
# This used to be every context in the platform -- thirty-seven streams -- with
# `reporting_applies_event_type` filtering them out again on the client. The
# filter decides whether the handler *projects* a fact; it does not save the
# read, the parse or the acknowledge, and the bus is one Redis thread that every
# other service shares. Measured at revision 459, `reporting` was the largest
# single consumer on the bus and its own lags were 0.0 to 0.1 seconds, so almost
# all of that work was for events it then discarded.
#
# The list below is derived twice and the two agree. Once from
# `_REPORTING_APPLIED_EVENT_TYPES` plus `REVENUE_DASHBOARD_SOURCE_EVENTS`, by
# keeping only the types that have a producer anywhere in this repository; and
# once from this service's own `processed_events`, whose non-empty streams are
# exactly these fourteen. Twelve of the applied types have no producer at all
# (`BookingConfirmed`, `CapacityUpdated`, `SeatInventoryUpdated`,
# `CapacityExhausted`, `TripSearched`, `SearchPerformed`, `OfferSearchRequested`,
# `PaymentDeclined`, `PaymentCaptureFailed`, `ScalperBlocked`,
# `RiskBookingBlocked`, `AncillaryQuoted`), so the streams that would have
# carried them were pure cost.
SUBSCRIBED_CONTEXTS = (
    "ancillary-service",
    "booking-orchestration",
    "customer-service",
    "dispatch",
    "finance-settlement",
    "fulfillment",
    "invoicing",
    "journey-order",
    "payment",
    "payment-channel",
    "post-sales",
    "seat-assignment",
    "travel-insurance",
    "waitlist",
)


def stream_for_producer(producer: str) -> str:
    return f"{STREAM_PREFIX}{producer}"


def dlq_for_stream(stream: str) -> str:
    return f"{stream}:dlq"


def reporting_subscription_streams() -> list[str]:
    return [stream_for_producer(context) for context in SUBSCRIBED_CONTEXTS]
