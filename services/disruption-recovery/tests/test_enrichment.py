from datetime import UTC, datetime, timedelta

from fastapi.testclient import TestClient

from disruption_recovery import create_app
from disruption_recovery.application.service import InMemoryStore
from disruption_recovery.domain import (
    AffectedBooking,
    AlternativeRoute,
    CompensationCalculator,
    DisruptionClassifier,
    DisruptionType,
    MassDisruptionProcessor,
    ReroutingDecision,
    ReroutingEngine,
    SeatClass,
    SeverityLevel,
)


def test_90_minute_delay_gets_25_percent_compensation() -> None:
    assert DisruptionClassifier.classify(DisruptionType.DELAY, 90) is SeverityLevel.MODERATE
    award = CompensationCalculator().calculate(10_000, 90, DisruptionType.DELAY)
    assert award.refundMinorUnits == 0
    assert award.compensationMinorUnits == 2_500


def test_force_majeure_cancellation_full_refund_without_extra_compensation() -> None:
    award = CompensationCalculator().calculate(10_000, 300, DisruptionType.FORCE_MAJEURE)
    assert award.refundMinorUnits == 10_000
    assert award.compensationMinorUnits == 0


def test_auto_rerouting_two_hours_later_same_class_scores_above_threshold() -> None:
    departure = datetime(2026, 8, 2, 10, tzinfo=UTC)
    booking = AffectedBooking("ord-1", "trav-1", "seg-old", originalDeparture=departure, seatClass=SeatClass.SECOND)
    alternative = AlternativeRoute("seg-new", departure + timedelta(hours=2), SeatClass.SECOND)

    outcome = ReroutingEngine().decide(booking, (alternative,))

    assert outcome.decision is ReroutingDecision.AUTO_REBOOK
    assert outcome.suggestion is not None
    assert outcome.suggestion.score > 80


def test_no_alternative_within_four_hours_offers_refund() -> None:
    departure = datetime(2026, 8, 2, 10, tzinfo=UTC)
    booking = AffectedBooking("ord-1", "trav-1", "seg-old", originalDeparture=departure, seatClass=SeatClass.SECOND)
    alternative = AlternativeRoute("seg-too-late", departure + timedelta(hours=5), SeatClass.SECOND)

    assert ReroutingEngine().decide(booking, (alternative,)).decision is ReroutingDecision.OFFER_REFUND


def test_500_affected_passengers_processed_in_five_batches_with_priority() -> None:
    departure = datetime(2026, 8, 2, 10, tzinfo=UTC)
    bookings = tuple(
        AffectedBooking(f"ord-{index:03d}", f"trav-{index:03d}", "seg-old", originalDeparture=departure, seatClass=SeatClass.BUSINESS if index == 499 else SeatClass.SECOND)
        for index in range(500)
    )
    alternative = AlternativeRoute("seg-new", departure + timedelta(hours=2), SeatClass.SECOND)
    batches, _, progress = MassDisruptionProcessor().process(bookings, (alternative,), lambda: "batch")

    assert len(batches) == 5
    assert progress.processed == 500
    assert batches[0].bookings[0].seatClass is SeatClass.BUSINESS


def cancellation_body(order_count: int = 2) -> dict[str, object]:
    departure = "2026-08-02T10:00:00Z"
    return {
        "disruptionType": "CANCELLATION",
        "scheduledServiceRef": "ssch-0194f2e0-7b3e-7610-8000-000000000201",
        "segmentRef": "seg-0194f2e0-7b3e-7610-8000-000000000202",
        "serviceDate": "2026-08-02",
        "evidence": {"evidenceRef": "ev-cancel", "sourceSystem": "ADMIN", "sourceRecordId": "row-cancel", "summary": "Cancelled"},
        "affectedOrderIds": [f"ord-{index:03d}" for index in range(order_count)],
        "reportedBy": {"actorType": "OPERATIONS", "actorId": "ops-1"},
        "originalDeparture": departure,
        "seatClass": "SECOND",
        "ticketPriceMinorUnits": 10000,
        "alternativeRoutes": [{"segmentRef": "seg-rebook", "departureTime": "2026-08-02T12:00:00Z", "seatClass": "SECOND", "transfers": 0}],
    }


def test_train_cancellation_batch_processing_starts_and_outbox_events_are_published() -> None:
    store = InMemoryStore()
    app = create_app(store=store)
    client = TestClient(app)

    response = client.post("/api/v1/disruptions", json=cancellation_body(), headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000310"})

    assert response.status_code == 202, response.text
    data = response.json()
    assert data["disruption"]["classification"]["severity"] == "CRITICAL"
    assert data["massRecovery"]["progress"]["processed"] == 2
    events = store.take_outbox()
    event_types = {event.eventType for event in events}
    assert "DisruptionDeclared" in event_types
    assert "MassDisruptionDetected" in event_types
    assert "BatchProcessingStarted" in event_types
    assert "PassengerRebooked" in event_types
    assert "CompensationIssued" in event_types
    assert "DisruptionResolved" in event_types
