from __future__ import annotations

PRODUCER_CONTEXT = "reporting"
CONSUMER_GROUP = "reporting"
STREAM_PREFIX = "events:"
STREAM_MAXLEN = 100_000
RETRY_LIMIT = 5
MIN_IDLE_MS = 60_000
BLOCK_MS = 2_000
READ_COUNT = 10

SUBSCRIBED_CONTEXTS = (
    "place-network",
    "service-plan",
    "capacity-availability",
    "fare-pricing",
    "trip-planning",
    "offer-management",
    "journey-order",
    "booking-orchestration",
    "payment",
    "provider-integration",
    "entitlement-ticketing",
    "fulfillment",
    "post-sales",
    "notification",
    "traveler-profile",
    "risk-compliance",
    "account",
    "admin-audit",
    "customer-service",
    "finance-settlement",
    "reporting",
    "supplier-catalog",
)


def stream_for_producer(producer: str) -> str:
    return f"{STREAM_PREFIX}{producer}"


def dlq_for_stream(stream: str) -> str:
    return f"{stream}:dlq"


def reporting_subscription_streams() -> list[str]:
    return [stream_for_producer(context) for context in SUBSCRIBED_CONTEXTS]
