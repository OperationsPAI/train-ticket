from datetime import UTC, datetime

import pytest

from disruption_recovery.domain import RecoveryCase, RecoveryCaseStatus, DomainError


def test_manual_review_must_not_close_directly() -> None:
    now = datetime.now(UTC)
    case = RecoveryCase("rcv-1", "inc-1", "ord-1", {"journeyOrderId": "ord-1", "serviceDate": "2026-01-01", "disruptionType": "WEATHER", "evidenceRef": "ev"}, RecoveryCaseStatus.MANUAL_REVIEW, now, now)
    with pytest.raises(DomainError):
        case.close(now)
