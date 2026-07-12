from __future__ import annotations

from train_ticket_platform.messaging import RedisEventPublisher, RedisEventSubscriber, default_consumer_name

RISK_COMPLIANCE_PRODUCER = "risk-compliance"
RISK_COMPLIANCE_SUBSCRIPTIONS: tuple[str, ...] = ("events:journey-order", "events:booking-orchestration")
RISK_COMPLIANCE_CONSUMER_GROUP = "risk-compliance"


def risk_compliance_consumer_name() -> str:
    return default_consumer_name(RISK_COMPLIANCE_CONSUMER_GROUP)

__all__ = [
    "RISK_COMPLIANCE_CONSUMER_GROUP",
    "RISK_COMPLIANCE_PRODUCER",
    "RISK_COMPLIANCE_SUBSCRIPTIONS",
    "RedisEventPublisher",
    "RedisEventSubscriber",
    "risk_compliance_consumer_name",
]
