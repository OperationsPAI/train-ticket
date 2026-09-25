from datetime import UTC, datetime
from dataclasses import dataclass, field

from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.messaging import HandlerStatus

from disruption_recovery.application.service import DisruptionRecoveryService, InMemoryStore
from disruption_recovery.domain import DomainError
from disruption_recovery.metrics import OpenTelemetrySegmentSignalMetrics


@dataclass
class RecordingSegmentSignalMetrics:
    records: list[tuple[str, str, str]] = field(default_factory=list)

    def record(self, signal_type: str, outcome: str, source: str) -> None:
        self.records.append((signal_type, outcome, source))


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
    metrics = RecordingSegmentSignalMetrics()
    service = DisruptionRecoveryService(store, segment_signal_metrics=metrics)
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
    assert metrics.records == [("delay", "recovery_created", "fulfillment")]


def test_provider_segment_cancellation_opens_case_with_provider_source() -> None:
    store = InMemoryStore()
    metrics = RecordingSegmentSignalMetrics()
    service = DisruptionRecoveryService(store, segment_signal_metrics=metrics)
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
    assert metrics.records == [("cancellation", "recovery_created", "provider")]


def test_segment_signal_without_affected_orders_is_an_audited_noop() -> None:
    store = InMemoryStore()
    metrics = RecordingSegmentSignalMetrics()
    service = DisruptionRecoveryService(store, segment_signal_metrics=metrics)
    delayed = envelope(
        "evt-0194f2e0-7b3e-7610-8000-000000000911",
        "SegmentDelayed",
        "fulfillment",
        {"segmentRef": "seg-missing", "scheduledServiceRef": "ssch-1", "serviceDate": "2026-08-02", "observedAt": "2026-08-02T09:00:00Z"},
    )

    result = service.handle_inbound_event(delayed, "events:fulfillment")

    assert result.status is HandlerStatus.SUCCESS
    assert delayed.eventId in store.processed_events
    assert not store.incidents
    assert not store.cases
    assert not store.take_outbox()
    assert metrics.records == [("delay", "zero_impact", "fulfillment")]


def test_segment_signal_resolver_failure_remains_retryable() -> None:
    class FailingStore(InMemoryStore):
        def find_orders_by_segment(self, segment_ref: str) -> tuple[str, ...]:
            raise RuntimeError("database unavailable")

    metrics = RecordingSegmentSignalMetrics()
    service = DisruptionRecoveryService(FailingStore(), segment_signal_metrics=metrics)
    delayed = envelope(
        "evt-0194f2e0-7b3e-7610-8000-000000000912",
        "SegmentDelayed",
        "fulfillment",
        {"segmentRef": "seg-lookup", "scheduledServiceRef": "ssch-2", "serviceDate": "2026-08-02", "observedAt": "2026-08-02T09:00:00Z"},
    )

    result = service.handle_inbound_event(delayed, "events:fulfillment")

    assert result.status is HandlerStatus.TRANSIENT_ERROR
    assert result.message == "database unavailable"
    assert metrics.records == [("delay", "retry", "fulfillment")]


def test_segment_signal_without_resolver_records_retry() -> None:
    class StoreWithoutResolver:
        pass

    metrics = RecordingSegmentSignalMetrics()
    service = DisruptionRecoveryService(
        StoreWithoutResolver(), segment_signal_metrics=metrics
    )
    cancelled = envelope(
        "evt-0194f2e0-7b3e-7610-8000-000000000921",
        "SegmentCancelled",
        "provider-integration",
        {"segmentRef": "seg-retry", "serviceDate": "2026-08-02"},
    )

    result = service.handle_inbound_event(cancelled, "events:provider-integration")

    assert result.status is HandlerStatus.TRANSIENT_ERROR
    assert metrics.records == [("cancellation", "retry", "provider")]


def test_invalid_segment_signal_records_bounded_source() -> None:
    metrics = RecordingSegmentSignalMetrics()
    service = DisruptionRecoveryService(InMemoryStore(), segment_signal_metrics=metrics)
    invalid = envelope(
        "evt-0194f2e0-7b3e-7610-8000-000000000913",
        "SegmentCancelled",
        "untrusted-provider-name",
        {"segmentRef": "seg-0194f2e0-7b3e-7610-8000-000000000914"},
    )

    result = service.handle_inbound_event(invalid, "events:untrusted-provider-name")

    assert result.status is HandlerStatus.FATAL_ERROR
    assert metrics.records == [("cancellation", "invalid", "other")]


def test_invalid_delay_payload_records_invalid() -> None:
    store = InMemoryStore()
    store.index_journey_order("ord-invalid", ("seg-invalid",))
    metrics = RecordingSegmentSignalMetrics()
    service = DisruptionRecoveryService(store, segment_signal_metrics=metrics)
    invalid = envelope(
        "evt-0194f2e0-7b3e-7610-8000-000000000922",
        "SegmentDelayed",
        "fulfillment",
        {
            "segmentRef": "seg-invalid",
            "serviceDate": "2026-08-02",
            "delayMinutes": "not-a-number",
        },
    )

    result = service.handle_inbound_event(invalid, "events:fulfillment")

    assert result.status is HandlerStatus.FATAL_ERROR
    assert metrics.records == [("delay", "invalid", "fulfillment")]


def test_segment_signal_report_failures_use_result_outcome() -> None:
    class FailingReportService(DisruptionRecoveryService):
        def __init__(self, store: InMemoryStore, error: Exception, metrics: RecordingSegmentSignalMetrics) -> None:
            super().__init__(store, segment_signal_metrics=metrics)
            self.error = error

        def report_disruption(self, data: dict, correlation_id: str, causation_id: str) -> dict:
            raise self.error

    for error, expected_status, expected_outcome in (
        (DomainError("invalid report"), HandlerStatus.FATAL_ERROR, "invalid"),
        (RuntimeError("database unavailable"), HandlerStatus.TRANSIENT_ERROR, "retry"),
    ):
        store = InMemoryStore()
        store.index_journey_order("ord-report", ("seg-report",))
        metrics = RecordingSegmentSignalMetrics()
        service = FailingReportService(store, error, metrics)
        cancelled = envelope(
            f"evt-report-{expected_outcome}",
            "SegmentCancelled",
            "provider-integration",
            {"segmentRef": "seg-report", "serviceDate": "2026-08-02"},
        )

        result = service.handle_inbound_event(cancelled, "events:provider-integration")

        assert result.status is expected_status
        assert metrics.records == [("cancellation", expected_outcome, "provider")]


def test_non_segment_event_does_not_record_segment_metric() -> None:
    metrics = RecordingSegmentSignalMetrics()
    service = DisruptionRecoveryService(InMemoryStore(), segment_signal_metrics=metrics)

    result = service.handle_segment_signal(
        envelope("evt-not-segment", "JourneyOrderCreated", "journey-order", {}),
        "events:journey-order",
    )

    assert result.status is HandlerStatus.SUCCESS
    assert metrics.records == []


def test_duplicate_segment_signal_is_counted_as_zero_impact() -> None:
    store = InMemoryStore()
    store.index_journey_order(
        "ord-0194f2e0-7b3e-7610-8000-000000000915",
        ("seg-0194f2e0-7b3e-7610-8000-000000000916",),
    )
    metrics = RecordingSegmentSignalMetrics()
    service = DisruptionRecoveryService(store, segment_signal_metrics=metrics)
    delayed = envelope(
        "evt-0194f2e0-7b3e-7610-8000-000000000917",
        "SegmentDelayed",
        "fulfillment",
        {
            "segmentRef": "seg-0194f2e0-7b3e-7610-8000-000000000916",
            "serviceDate": "2026-08-02",
            "delayMinutes": 15,
            "estimatedArrivalAt": "2026-08-02T10:15:00Z",
        },
    )

    assert service.handle_inbound_event(delayed, "events:fulfillment").status is HandlerStatus.SUCCESS
    assert service.handle_inbound_event(delayed, "events:fulfillment").status is HandlerStatus.SUCCESS

    assert metrics.records == [
        ("delay", "recovery_created", "fulfillment"),
        ("delay", "zero_impact", "fulfillment"),
    ]


def test_segment_signal_metric_has_only_three_bounded_labels() -> None:
    reader = InMemoryMetricReader()
    provider = MeterProvider(metric_readers=[reader])
    try:
        metrics = OpenTelemetrySegmentSignalMetrics(provider.get_meter("disruption-recovery-test"))
        store = InMemoryStore()
        service = DisruptionRecoveryService(store, segment_signal_metrics=metrics)
        for suffix in ("918", "919"):
            segment_ref = f"seg-0194f2e0-7b3e-7610-8000-000000000{suffix}"
            store.index_journey_order(
                f"ord-0194f2e0-7b3e-7610-8000-000000000{suffix}",
                (segment_ref,),
            )
            signal = envelope(
                f"evt-0194f2e0-7b3e-7610-8000-000000000{suffix}",
                "SegmentDelayed",
                f"dynamic-producer-{suffix}",
                {
                    "segmentRef": segment_ref,
                    "scheduledServiceRef": f"ssch-0194f2e0-7b3e-7610-8000-000000000{suffix}",
                    "serviceDate": "2026-08-02",
                    "delayMinutes": 20,
                    "estimatedArrivalAt": "2026-08-02T10:20:00Z",
                },
            )
            assert service.handle_inbound_event(signal, f"events:dynamic-{suffix}").status is HandlerStatus.SUCCESS

        data = reader.get_metrics_data()
        exported = [
            metric
            for resource_metrics in data.resource_metrics
            for scope_metrics in resource_metrics.scope_metrics
            for metric in scope_metrics.metrics
            if metric.name == "train_ticket.disruption.segment_signals"
        ]
        assert len(exported) == 1
        points = list(exported[0].data.data_points)
        assert len(points) == 1
        assert points[0].value == 2
        assert dict(points[0].attributes) == {
            "signal_type": "delay",
            "outcome": "recovery_created",
            "source": "other",
        }
    finally:
        provider.shutdown()


def test_segment_signal_metric_failure_does_not_change_handler_result() -> None:
    class FailingMetrics:
        def record(self, signal_type: str, outcome: str, source: str) -> None:
            raise RuntimeError("metrics unavailable")

    service = DisruptionRecoveryService(
        InMemoryStore(), segment_signal_metrics=FailingMetrics()
    )
    delayed = envelope(
        "evt-0194f2e0-7b3e-7610-8000-000000000920",
        "SegmentDelayed",
        "fulfillment",
        {
            "segmentRef": "seg-no-orders",
            "serviceDate": "2026-08-02",
            "estimatedArrivalAt": "2026-08-02T10:20:00Z",
        },
    )

    assert service.handle_inbound_event(delayed, "events:fulfillment").status is HandlerStatus.SUCCESS
