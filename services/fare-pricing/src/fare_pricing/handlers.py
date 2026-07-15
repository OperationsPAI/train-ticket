from __future__ import annotations

import logging
from datetime import date, datetime
from typing import Any, Mapping

from fare_pricing.application.service import FarePricingService
from fare_pricing.domain import CapacitySnapshot, EligibilityCertificateSummary, PricingError
from fare_pricing.ports import EventEnvelope

CAPACITY_STREAM = "events:capacity-availability"
CAPACITY_EVENT_TYPE = "CapacitySnapshotUpdated"
IDENTITY_VERIFICATION_STREAM = "events:identity-verification"
ELIGIBILITY_CERTIFICATE_EVENT_TYPES = frozenset({"EligibilityCertificateRegistered", "EligibilityCertificateVerified"})
ELIGIBILITY_USAGE_EVENT_TYPES = frozenset({"EligibilityUsageReserved", "EligibilityUsageConfirmed", "EligibilityUsageReleased"})
IDENTITY_VERIFICATION_EVENT_TYPES = ELIGIBILITY_CERTIFICATE_EVENT_TYPES | ELIGIBILITY_USAGE_EVENT_TYPES
LOGGER = logging.getLogger(__name__)


def handle_capacity_snapshot_updated(service: FarePricingService, envelope: EventEnvelope) -> None:
    """Cache CapacitySnapshotUpdated events for quote-time dynamic pricing."""
    if envelope.event_type != CAPACITY_EVENT_TYPE:
        return
    snapshot = capacity_snapshot_from_payload(envelope.payload)
    service.upsert_capacity_snapshot(snapshot)


def handle_identity_verification_event(service: FarePricingService, envelope: EventEnvelope) -> None:
    """Maintain fare-pricing's read-only eligibility-certificate model."""
    if envelope.producer != "identity-verification":
        return
    certificate = eligibility_certificate_from_payload(envelope.payload) if envelope.eventType in ELIGIBILITY_CERTIFICATE_EVENT_TYPES else None
    usage_counts = _eligibility_usage_counts_from_payload(envelope.payload) if envelope.eventType in ELIGIBILITY_USAGE_EVENT_TYPES else None
    with service.transaction():
        if not service.mark_event_processed(envelope.eventId, IDENTITY_VERIFICATION_STREAM):
            return
        if certificate is not None:
            service.upsert_eligibility_certificate(certificate)
            return
        if usage_counts is not None:
            service.update_eligibility_usage_counts(*usage_counts)
            return
        LOGGER.warning(
            "ack-skip fare-pricing unknown identity-verification eventType=%s eventId=%s",
            envelope.eventType,
            envelope.eventId,
        )


def capacity_snapshot_from_payload(payload: Mapping[str, Any]) -> CapacitySnapshot:
    try:
        return CapacitySnapshot(
            segment_ref=str(payload["segmentRef"]),
            departure_date=date.fromisoformat(str(payload["departureDate"])),
            total_capacity=int(payload["totalCapacity"]),
            remaining_capacity=int(payload["remainingCapacity"]),
            snapshot_version=int(payload["snapshotVersion"]),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise PricingError("invalid CapacitySnapshotUpdated payload") from exc


def eligibility_certificate_from_payload(payload: Mapping[str, Any]) -> EligibilityCertificateSummary:
    try:
        return EligibilityCertificateSummary(
            eligibility_certificate_id=str(payload["eligibilityCertificateId"]),
            traveler_id=str(payload["travelerId"]),
            eligibility_type=str(payload["eligibilityType"]),
            certificate_status=str(payload["certificateStatus"]),
            valid_from=_parse_rfc3339(str(payload["validFrom"])),
            valid_until=_parse_rfc3339(str(payload["validUntil"])),
            policy_year=str(payload["policyYear"]),
            policy_version=str(payload["policyVersion"]),
            annual_usage_limit=int(payload["annualUsageLimit"]),
            annual_usage_reserved=int(payload["annualUsageReserved"]),
            annual_usage_confirmed=int(payload["annualUsageConfirmed"]),
            applicable_product_codes=tuple(str(item) for item in payload["applicableProductCodes"]),
            aggregate_version=int(payload["aggregateVersion"]),
            credential_record_id=str(payload["credentialRecordId"]) if payload.get("credentialRecordId") else None,
            identity_cluster_id=str(payload["identityClusterId"]) if payload.get("identityClusterId") else None,
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise PricingError("invalid eligibility certificate payload") from exc


def _eligibility_usage_counts_from_payload(payload: Mapping[str, Any]) -> tuple[str, int, int, int]:
    try:
        return (
            str(payload["eligibilityCertificateId"]),
            int(payload["annualUsageReserved"]),
            int(payload["annualUsageConfirmed"]),
            int(payload["aggregateVersion"]),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise PricingError("invalid eligibility usage payload") from exc


def _parse_rfc3339(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))
