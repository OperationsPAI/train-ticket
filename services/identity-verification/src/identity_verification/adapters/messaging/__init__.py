from __future__ import annotations

from typing import TYPE_CHECKING

from train_ticket_platform.events import EventEnvelope

if TYPE_CHECKING:
    from ...application.service import IdentityVerificationService

TRAVELER_PROFILE_STREAM = "events:traveler-profile"
JOURNEY_ORDER_STREAM = "events:journey-order"
RISK_COMPLIANCE_STREAM = "events:risk-compliance"
IDENTITY_STREAM = "events:identity-verification"


def handle_upstream_event(service: IdentityVerificationService, envelope: EventEnvelope) -> None:
    if envelope.producer == "journey-order" and envelope.eventType in {
        "JourneyOrderCreated",
        "JourneyOrderCancelled",
    }:
        service.handle_journey_order_event(envelope, JOURNEY_ORDER_STREAM)
    elif envelope.producer == "risk-compliance" and envelope.eventType == "RiskAlertRaised":
        service.handle_risk_alert_raised(envelope, RISK_COMPLIANCE_STREAM)
    elif envelope.producer == "traveler-profile" and envelope.eventType == "TravelerSnapshotUpdated":
        service.handle_traveler_snapshot_updated(envelope, TRAVELER_PROFILE_STREAM)


__all__ = [
    "IDENTITY_STREAM",
    "JOURNEY_ORDER_STREAM",
    "RISK_COMPLIANCE_STREAM",
    "TRAVELER_PROFILE_STREAM",
    "handle_upstream_event",
]
