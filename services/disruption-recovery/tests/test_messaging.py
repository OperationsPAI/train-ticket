from datetime import UTC, datetime

from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.messaging import HandlerStatus

from disruption_recovery.application.service import DisruptionRecoveryService, InMemoryStore


def envelope(event_id: str, event_type: str, producer: str, payload: dict) -> EventEnvelope:
    return EventEnvelope(
        eventId=event_id,
        eventType=event_type,
        occurredAt=datetime(2026, 8, 2, 9, tzinfo=UTC),
        correlationId="corr-0194f2e0-7b3e-7610-8000-000000000900",
        causationId="cmd-0194f2e0-7b3e-7610-8000-000000000901",
        producer=producer,
        schemaVersion=1,
        payload=payload,
    )


def test_fulfillment_segment_signal_fans_out_to_indexed_orders_and_alert_model() -> None:
    store = InMemoryStore()
    service = DisruptionRecoveryService(store)
    created = envelope(
        "evt-0194f2e0-7b3e-7610-8000-000000000902",
        "JourneyOrderCreated",
        "journey-order",
        {"orderId": "ord-0194f2e0-7b3e-7610-8000-000000000903", "segmentRefs": ["seg-0194f2e0-7b3e-7610-8000-000000000904"]},
    )
    assert service.handle_inbound_event(created, "events:journey-order").status is HandlerStatus.SUCCESS

    delayed = envelope(
        "evt-0194f2e0-7b3e-7610-8000-000000000905",
        "SegmentDelayed",
        "fulfillment",
        {
            "segmentRef": "seg-0194f2e0-7b3e-7610-8000-000000000904",
            "scheduledServiceRef": "ssch-0194f2e0-7b3e-7610-8000-000000000906",
            "serviceDate": "2026-08-02",
            "estimatedArrivalAt": "2026-08-02T10:45:00Z",
            "observedAt": "2026-08-02T09:30:00Z",
            "sourceSystem": "OPS",
        },
    )
    result = service.handle_inbound_event(delayed, "events:fulfillment")
    assert result.status is HandlerStatus.SUCCESS
    assert len(store.cases) == 1
    case = next(iter(store.cases.values()))
    assert case.journeyOrderId == "ord-0194f2e0-7b3e-7610-8000-000000000903"
    assert case.affectedScope["disruptionType"] == "DELAY"
    assert next(iter(store.service_alerts.values())).affectedOrderIds == (case.journeyOrderId,)


def test_provider_segment_cancellation_opens_case_with_provider_source() -> None:
    store = InMemoryStore()
    service = DisruptionRecoveryService(store)
    store.index_journey_order("ord-0194f2e0-7b3e-7610-8000-000000000907", ("seg-0194f2e0-7b3e-7610-8000-000000000908",))
    cancelled = envelope(
        "evt-0194f2e0-7b3e-7610-8000-000000000909",
        "SegmentCancelled",
        "provider-integration",
        {
            "segmentRef": "seg-0194f2e0-7b3e-7610-8000-000000000908",
            "scheduledServiceRef": "ssch-0194f2e0-7b3e-7610-8000-000000000910",
            "serviceDate": "2026-08-02",
            "cancelledAt": "2026-08-02T08:00:00Z",
            "observedAt": "2026-08-02T08:00:00Z",
        },
    )

    result = service.handle_inbound_event(cancelled, "events:provider-integration")

    assert result.status is HandlerStatus.SUCCESS
    event_payloads = [event.payload for event in store.take_outbox() if event.eventType == "DisruptionReported"]
    assert event_payloads[0]["disruptionType"] == "CANCELLATION"
    assert event_payloads[0]["evidence"]["sourceSystem"] == "PROVIDER_INTEGRATION"
    assert next(iter(store.cases.values())).affectedScope["disruptionType"] == "CANCELLATION"


def test_unresolved_segment_signal_returns_transient_for_redis_retry() -> None:
    service = DisruptionRecoveryService(InMemoryStore())
    delayed = envelope(
        "evt-0194f2e0-7b3e-7610-8000-000000000911",
        "SegmentDelayed",
        "fulfillment",
        {"segmentRef": "seg-missing", "scheduledServiceRef": "ssch-1", "serviceDate": "2026-08-02", "observedAt": "2026-08-02T09:00:00Z"},
    )

    result = service.handle_inbound_event(delayed, "events:fulfillment")

    assert result.status is HandlerStatus.TRANSIENT_ERROR
