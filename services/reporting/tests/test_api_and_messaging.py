from __future__ import annotations

import json
import unittest
from datetime import UTC, datetime
from uuid import UUID
from typing import Sequence

from fastapi import FastAPI
from fastapi.testclient import TestClient

from reporting import create_app
from reporting.api import configure_error_handlers, configure_runtime_endpoints
from reporting.application.ports import EventEnvelope, EventHandler, EventPublisher, EventSubscriber, HandlerResult
from reporting.application.service import ReportingApplicationService
from reporting.adapters.messaging.publisher import RedisEventPublisher
from reporting.adapters.messaging.stream_config import SUBSCRIBED_CONTEXTS, reporting_subscription_streams
from reporting.adapters.messaging.subscriber import RedisEventSubscriber
from reporting.domain import ReadModelStatus, ReportingError


class FakePublisher(EventPublisher):
    def __init__(self) -> None:
        self.published: list[EventEnvelope] = []

    def publish(self, envelope: EventEnvelope) -> None:
        self.published.append(envelope)


class FakeSubscriber(EventSubscriber):
    def __init__(self) -> None:
        self.seen: set[str] = set()
        self.handled: list[str] = []
        self.handler: EventHandler | None = None

    def subscribe(self, streams: Sequence[str], group: str, consumer_name: str, handler: EventHandler) -> None:
        self.handler = handler

    def stop(self) -> None:
        pass

    def receive(self, envelope: EventEnvelope) -> HandlerResult:
        if envelope.eventId in self.seen:
            return HandlerResult.success()
        self.seen.add(envelope.eventId)
        self.handled.append(envelope.eventId)
        assert self.handler is not None
        return self.handler(envelope)


class FakeRedis:
    def __init__(self) -> None:
        self.entries: list[tuple[str, dict[str, str], int, bool]] = []

    def xadd(self, name: str, fields: dict[str, str], maxlen: int, approximate: bool) -> None:
        self.entries.append((name, fields, maxlen, approximate))


class EndpointTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = TestClient(create_app())

    def test_list_metrics_happy_path(self) -> None:
        response = self.client.get("/api/v1/metrics?category=financial&limit=20&offset=0")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["limit"], 20)
        self.assertEqual(body["offset"], 0)
        self.assertEqual(body["items"][0]["metricId"], "metric-revenue")

    def test_get_metric_happy_path(self) -> None:
        response = self.client.get("/api/v1/metrics/metric-revenue")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["expression"], "SUM(payment.capturedAmount.minorUnits)")

    def test_query_dashboard_happy_path(self) -> None:
        response = self.client.get("/api/v1/dashboards/dash-revenue")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["dashboardId"], "dash-revenue")
        self.assertEqual(response.json()["currentSnapshot"]["rebuildId"], "rebuild-revenue-001")

    def test_list_dashboard_rebuilds_happy_path(self) -> None:
        response = self.client.get("/api/v1/dashboards/dash-revenue/rebuilds?limit=20&offset=0")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["items"][0]["status"], "COMPLETED")

    def test_validation_failure_uses_canonical_400_body(self) -> None:
        response = self.client.get("/api/v1/metrics?limit=101", headers={"X-Correlation-Id": "corr-test"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["code"], "VALIDATION_FAILED")
        self.assertEqual(response.json()["correlationId"], "corr-test")
        self.assertIn("details", response.json())

    def test_not_found_uses_canonical_body(self) -> None:
        response = self.client.get("/api/v1/metrics/missing")
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.json()["code"], "NOT_FOUND")

    def test_unknown_route_uses_canonical_not_found_body(self) -> None:
        response = self.client.get("/api/v1/does-not-exist", headers={"X-Correlation-Id": "corr-route-test"})
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.json(), {"code": "NOT_FOUND", "message": "Not Found", "correlationId": "corr-route-test", "details": {}})

    def test_idempotent_replay_returns_original_result(self) -> None:
        app = create_app()

        @app.post("/api/v1/test-command")
        async def command() -> dict[str, str]:
            return {"result": "created"}

        client = TestClient(app)
        headers = {"Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c001"}
        first = client.post("/api/v1/test-command", json={"a": 1}, headers=headers)
        second = client.post("/api/v1/test-command", json={"a": 1}, headers=headers)
        reused = client.post("/api/v1/test-command", json={"a": 2}, headers=headers)
        missing = client.post("/api/v1/test-command", json={"a": 1})
        self.assertEqual(first.status_code, 200)
        self.assertEqual(second.json(), first.json())
        self.assertEqual(reused.status_code, 422)
        self.assertEqual(reused.json()["code"], "IDEMPOTENCY_KEY_REUSED")
        malformed = client.post("/api/v1/test-command", json={"a": 1}, headers={"Idempotency-Key": "not-a-uuid"})
        v4 = client.post("/api/v1/test-command", json={"a": 1}, headers={"Idempotency-Key": "550e8400-e29b-41d4-a716-446655440000"})
        self.assertEqual(missing.status_code, 400)
        self.assertEqual(missing.json()["code"], "VALIDATION_FAILED")
        self.assertEqual(malformed.status_code, 400)
        self.assertEqual(malformed.json()["code"], "VALIDATION_FAILED")
        self.assertEqual(v4.status_code, 400)
        self.assertEqual(v4.json()["code"], "VALIDATION_FAILED")

    def test_domain_error_surfaces_as_domain_rule_violation(self) -> None:
        app = FastAPI()
        configure_error_handlers(app)
        configure_runtime_endpoints(app)

        @app.get("/boom")
        async def boom() -> None:
            raise ReportingError("domain invariant failed")

        response = TestClient(app).get("/boom")
        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "DOMAIN_RULE_VIOLATION")

    def test_dashboard_operational_metrics_endpoint(self) -> None:
        service = ReportingApplicationService()
        occurred_at = datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
        for index in range(10):
            service.handle_event(EventEnvelope(
                eventId=f"evt-order-{index}",
                eventType="JourneyOrderCreated",
                occurredAt=occurred_at,
                correlationId="corr-1",
                producer="journey-order",
                schemaVersion=1,
                payload={"routeId": "G123", "bookingLatencyMs": 120},
                causationId="evt-source",
            ))
        client = TestClient(create_app(service=service))

        response = client.get("/api/v1/metrics/operational")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["orders_per_second"], 10)
        self.assertIn("avg_booking_latency", response.json())

    def test_anomaly_detection_publishes_event(self) -> None:
        publisher = FakePublisher()
        service = ReportingApplicationService(publisher=publisher)
        occurred_at = datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
        with self.assertLogs("reporting.application.service", level="WARNING") as logs:
            for index in range(8):
                service.handle_event(EventEnvelope(eventId=f"evt-pay-{index}", eventType="PaymentCaptured", occurredAt=occurred_at, correlationId="corr-1", producer="payment", schemaVersion=1, payload={"amount": "10.00", "currency": "USD"}, causationId="evt-source"))
            for index in range(2):
                service.handle_event(EventEnvelope(eventId=f"evt-fail-{index}", eventType="PaymentFailed", occurredAt=occurred_at, correlationId="corr-1", producer="payment", schemaVersion=1, payload={}, causationId="evt-source"))

        anomaly_events = [event for event in publisher.published if event.eventType == "AnomalyDetected"]
        urgent_events = [event for event in publisher.published if event.eventType == "UrgentNotificationRequested"]

        self.assertTrue(anomaly_events)
        self.assertEqual(anomaly_events[-1].payload["ruleId"], "ERROR_RATE_SPIKE")
        self.assertTrue(urgent_events)
        self.assertEqual(urgent_events[-1].payload["ruleId"], "ERROR_RATE_SPIKE")
        self.assertTrue(any("ERROR_RATE_SPIKE" in message for message in logs.output))

    def test_revenue_endpoint_returns_route_breakdown(self) -> None:
        service = ReportingApplicationService()
        service.handle_event(EventEnvelope(eventId="evt-r-a", eventType="PaymentCaptured", occurredAt="2026-07-05T10:30:00.000Z", correlationId="corr-1", producer="payment", schemaVersion=1, payload={"routeId": "A", "amount": "50.00", "currency": "USD"}, causationId="evt-source"))
        service.handle_event(EventEnvelope(eventId="evt-r-b", eventType="PaymentCaptured", occurredAt="2026-07-05T10:30:00.000Z", correlationId="corr-1", producer="payment", schemaVersion=1, payload={"routeId": "B", "amount": "75.00", "currency": "USD"}, causationId="evt-source"))

        response = TestClient(create_app(service=service)).get("/api/v1/metrics/revenue?groupBy=route")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["items"][0]["value"], "B")

    def test_new_context_events_increment_context_rollup_and_anomaly_signal(self) -> None:
        service = ReportingApplicationService()
        service.handle_event(EventEnvelope(
            eventId="evt-waitlist-queued",
            eventType="WaitlistQueued",
            occurredAt="2026-07-05T10:30:00.000Z",
            correlationId="corr-1",
            producer="waitlist",
            schemaVersion=1,
            payload={"waitlistRequestId": "wlr-1", "segmentRef": "seg-1"},
            causationId="evt-source",
        ))
        service.handle_event(EventEnvelope(
            eventId="evt-waitlist-expired",
            eventType="WaitlistExpired",
            occurredAt="2026-07-05T10:31:00.000Z",
            correlationId="corr-1",
            producer="waitlist",
            schemaVersion=1,
            payload={"waitlistRequestId": "wlr-2", "segmentRef": "seg-1"},
            causationId="evt-source",
        ))

        response = TestClient(create_app(service=service)).get("/api/v1/metrics/operational")

        self.assertEqual(response.status_code, 200)
        rollups = {(item["sourceContext"], item["eventType"]): item for item in response.json()["contextRollups"]}
        self.assertEqual(rollups[("waitlist", "WaitlistQueued")]["count"], 1)
        self.assertEqual(rollups[("waitlist", "WaitlistExpired")]["anomalyCount"], 1)

    def test_context_rollup_is_queryable_via_revenue_report_shape(self) -> None:
        service = ReportingApplicationService()
        service.handle_event(EventEnvelope(eventId="evt-dispatch-failed", eventType="DispatchFailed", occurredAt="2026-07-05T10:30:00.000Z", correlationId="corr-1", producer="dispatch", schemaVersion=1, payload={"dispatchId": "disp-1"}, causationId="evt-source"))
        service.handle_event(EventEnvelope(eventId="evt-dispatch-requested", eventType="DispatchRequested", occurredAt="2026-07-05T10:30:01.000Z", correlationId="corr-1", producer="dispatch", schemaVersion=1, payload={"dispatchId": "disp-2"}, causationId="evt-source"))
        service.handle_event(EventEnvelope(eventId="evt-waitlist-queued", eventType="WaitlistQueued", occurredAt="2026-07-05T10:30:02.000Z", correlationId="corr-1", producer="waitlist", schemaVersion=1, payload={"waitlistRequestId": "wlr-1"}, causationId="evt-source"))
        service.handle_event(EventEnvelope(eventId="evt-ancillary-quoted", eventType="AncillaryQuoted", occurredAt="2026-07-05T10:30:03.000Z", correlationId="corr-1", producer="ancillary-service", schemaVersion=1, payload={"quoteId": "anc-1"}, causationId="evt-source"))

        response = TestClient(create_app(service=service)).get("/api/v1/metrics/revenue?groupBy=source_context")

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual([item["value"] for item in body["items"]], ["dispatch", "ancillary-service", "waitlist"])
        self.assertEqual([item["count"] for item in body["items"]], [2, 1, 1])
        self.assertEqual(body["items"][0]["revenue"], {"amount": "0.00", "currency": "USD"})
        self.assertEqual(body["totalRevenue"], {"amount": "0.00", "currency": "USD"})


class MessagingTest(unittest.TestCase):
    def test_reporting_subscribes_to_all_phase_three_context_streams(self) -> None:
        missing_contexts = {
            "disruption-recovery",
            "transfer-management",
            "waitlist",
            "wallet-promotion",
            "dispatch",
            "ancillary-service",
            "loyalty-membership",
            "corporate-travel",
            "travel-insurance",
            "identity-verification",
            "payment-channel",
            "group-booking",
            "marketing-campaign",
            "invoicing",
            "seat-assignment",
        }
        self.assertTrue(missing_contexts.issubset(set(SUBSCRIBED_CONTEXTS)))
        self.assertTrue({f"events:{context}" for context in missing_contexts}.issubset(set(reporting_subscription_streams())))

    def test_application_publisher_wraps_event_in_correct_envelope(self) -> None:
        publisher = FakePublisher()
        service = ReportingApplicationService(publisher=publisher)
        envelope = service.publish_domain_event(
            event_type="DashboardRefreshed",
            payload={"dashboardId": "dash-revenue"},
            correlation_id="corr-018ff000-0000-7000-8000-000000000002",
            causation_id="cmd-018ff000-0000-7000-8000-000000000003",
            occurred_at=datetime(2026, 7, 5, 10, 30, tzinfo=UTC),
        )
        self.assertEqual(publisher.published, [envelope])
        self.assertTrue(envelope.eventId.startswith("evt-"))
        self.assertEqual(UUID(envelope.eventId.removeprefix("evt-")).version, 7)
        self.assertEqual(envelope.eventType, "DashboardRefreshed")
        self.assertEqual(envelope.occurredAt, "2026-07-05T10:30:00.000Z")
        self.assertEqual(envelope.producer, "reporting")
        self.assertEqual(envelope.schemaVersion, 1)
        self.assertEqual(UUID(envelope.correlationId.removeprefix("corr-")).version, 7)
        self.assertEqual(set(envelope.as_dict()), {"eventId", "eventType", "occurredAt", "correlationId", "causationId", "producer", "schemaVersion", "payload"})

    def test_redis_publisher_serializes_single_envelope_field(self) -> None:
        publisher = object.__new__(RedisEventPublisher)
        publisher._client = FakeRedis()
        envelope = EventEnvelope(
            eventId="evt-018ff000-0000-7000-8000-000000000004",
            eventType="ReportGenerated",
            occurredAt="2026-07-05T10:30:00.000Z",
            correlationId="corr-018ff000-0000-7000-8000-000000000005",
            producer="reporting",
            schemaVersion=1,
            payload={"reportId": "report-1"},
            causationId="cmd-018ff000-0000-7000-8000-000000000006",
        )
        publisher.publish(envelope)
        stream, fields, maxlen, approximate = publisher._client.entries[0]
        self.assertEqual(stream, "events:reporting")
        self.assertEqual(set(fields), {"envelope"})
        self.assertEqual(json.loads(fields["envelope"]), envelope.as_dict())
        # 10_000, matching python-kit's EVENT_STREAM_MAXLEN default. The cap was
        # lowered from 100_000 across all five kits after Redis was OOMKilled three
        # times; this assertion was missed in that pass and had been failing since.
        self.assertEqual(maxlen, 10_000)
        self.assertTrue(approximate)

    def test_application_publisher_omits_absent_optional_causation_id(self) -> None:
        envelope = ReportingApplicationService(publisher=FakePublisher()).publish_domain_event(
            event_type="DashboardRefreshed",
            payload={"dashboardId": "dash-revenue"},
            correlation_id=None,
            causation_id=None,
            occurred_at=datetime(2026, 7, 5, 10, 30, tzinfo=UTC),
        )
        self.assertIsNone(envelope.causationId)
        self.assertNotIn("causationId", envelope.as_dict())
        self.assertEqual(UUID(envelope.eventId.removeprefix("evt-")).version, 7)
        self.assertEqual(UUID(envelope.correlationId.removeprefix("corr-")).version, 7)

    def test_redis_subscriber_accepts_minimal_and_causation_envelopes(self) -> None:
        subscriber = object.__new__(RedisEventSubscriber)
        minimal = {
            "eventId": "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c020",
            "eventType": "PaymentCaptured",
            "occurredAt": "2026-07-05T10:30:00.000Z",
            "correlationId": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c021",
            "producer": "payment",
            "schemaVersion": 1,
            "payload": {},
        }
        with_causation = {
            **minimal,
            "eventId": "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c022",
            "causationId": "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c023",
        }
        self.assertIsNone(subscriber._deserialize_envelope(minimal).causationId)
        self.assertEqual(subscriber._deserialize_envelope(with_causation).causationId, with_causation["causationId"])


    def test_reporting_projection_exception_is_transient_not_fatal(self) -> None:
        class FailingRepository:
            def record_consumed_event(self, _: EventEnvelope) -> bool:
                raise RuntimeError("projection store unavailable")

        service = ReportingApplicationService(repository=FailingRepository())  # type: ignore[arg-type]
        envelope = EventEnvelope(
            eventId="evt-reporting-transient",
            eventType="PaymentCaptured",
            occurredAt="2026-07-05T10:30:00.000Z",
            correlationId="corr-1",
            producer="payment",
            schemaVersion=1,
            payload={},
            causationId="evt-1",
        )

        result = service.handle_event(envelope)

        self.assertEqual(result.status.value, "TRANSIENT_ERROR")
        self.assertIn("projection store unavailable", result.message)

    def test_unmodeled_event_is_ack_skipped_without_projection(self) -> None:
        publisher = FakePublisher()
        service = ReportingApplicationService(publisher=publisher)

        result = service.handle_event(EventEnvelope(
            eventId="evt-seat-assignment-skip",
            eventType="SeatAllocated",
            occurredAt="2026-07-16T10:30:00.000Z",
            correlationId="corr-1",
            producer="seat-assignment",
            schemaVersion=1,
            payload={"seatAllocationId": "salloc-1", "segmentBookingId": "sb-1"},
            causationId="evt-source",
        ))

        self.assertEqual(result.status.value, "SUCCESS")
        self.assertFalse(service.repository.consumed_events.has_consumed("evt-seat-assignment-skip"))
        self.assertEqual(service.repository.metric_aggregator.events, [])
        self.assertEqual(publisher.published, [])

    def test_revenue_event_rebuilds_dashboard_and_publishes_read_model_fact(self) -> None:
        publisher = FakePublisher()
        service = ReportingApplicationService(publisher=publisher)
        dashboard_before = service.get_dashboard("dash-revenue")
        assert dashboard_before is not None
        assert dashboard_before.current_snapshot is not None

        result = service.handle_event(EventEnvelope(
            eventId="evt-reporting-revenue-rebuild",
            eventType="PaymentCaptured",
            occurredAt="2026-07-16T10:30:00.000Z",
            correlationId="corr-1",
            producer="payment",
            schemaVersion=1,
            payload={"capturedAmount": {"currency": "USD", "minorUnits": 35000}},
            causationId="evt-source",
        ))

        dashboard_after = service.get_dashboard("dash-revenue")
        assert dashboard_after is not None
        assert dashboard_after.current_snapshot is not None
        rebuild_events = [event for event in publisher.published if event.eventType == "ReadModelRebuilt"]
        self.assertEqual(result.status.value, "SUCCESS")
        self.assertEqual(dashboard_after.status, ReadModelStatus.READY)
        self.assertGreater(dashboard_after.current_snapshot.event_count, dashboard_before.current_snapshot.event_count)
        self.assertTrue(rebuild_events)
        self.assertEqual(rebuild_events[-1].payload["dashboardId"], "dash-revenue")
        self.assertEqual(rebuild_events[-1].payload["eventCount"], dashboard_after.current_snapshot.event_count)
        self.assertEqual(str(service.revenue_report().total_revenue.amount), "350.00")
        self.assertEqual(service.revenue_report().total_revenue.currency, "USD")

        result = service.handle_event(EventEnvelope(
            eventId="evt-reporting-order-confirmed-rebuild",
            eventType="JourneyOrderConfirmed",
            occurredAt="2026-07-16T10:31:00.000Z",
            correlationId="corr-1",
            producer="journey-order",
            schemaVersion=1,
            payload={"journeyOrderId": "ord-1", "routeId": "G123"},
            causationId="evt-source",
        ))

        dashboard_after_order = service.get_dashboard("dash-revenue")
        assert dashboard_after_order is not None
        assert dashboard_after_order.current_snapshot is not None
        rebuild_events = [event for event in publisher.published if event.eventType == "ReadModelRebuilt"]
        self.assertEqual(result.status.value, "SUCCESS")
        self.assertEqual(dashboard_after_order.status, ReadModelStatus.READY)
        self.assertGreater(dashboard_after_order.current_snapshot.event_count, dashboard_after.current_snapshot.event_count)
        self.assertEqual(len(rebuild_events), 2)
        self.assertEqual(rebuild_events[-1].payload["dashboardId"], "dash-revenue")
        self.assertEqual(rebuild_events[-1].payload["eventCount"], dashboard_after_order.current_snapshot.event_count)

    def test_subscriber_dedups_duplicate_event_id(self) -> None:
        service = ReportingApplicationService()
        subscriber = FakeSubscriber()
        subscriber.subscribe(["events:payment"], "reporting", "reporting-test", service.handle_event)
        envelope = EventEnvelope(
            eventId="evt-duplicate",
            eventType="PaymentCaptured",
            occurredAt="2026-07-05T10:30:00.000Z",
            correlationId="corr-1",
            producer="payment",
            schemaVersion=1,
            payload={},
            causationId="evt-1",
        )
        self.assertEqual(subscriber.receive(envelope).status.value, "SUCCESS")
        self.assertEqual(subscriber.receive(envelope).status.value, "SUCCESS")
        self.assertEqual(subscriber.handled, ["evt-duplicate"])

    def test_redis_subscriber_dedups_duplicate_event_id(self) -> None:
        subscriber = object.__new__(RedisEventSubscriber)
        subscriber._seen_event_ids = set()
        from threading import Lock

        subscriber._seen_event_ids_lock = Lock()
        acks: list[str] = []

        class Client:
            def xack(self, stream: str, group: str, entry_id: str) -> None:
                acks.append(entry_id)

            def xadd(self, *args: object, **kwargs: object) -> None:
                raise AssertionError("duplicate should be acked, not DLQed")

        subscriber._client = Client()
        envelope = EventEnvelope(
            eventId="evt-redis-duplicate",
            eventType="PaymentCaptured",
            occurredAt="2026-07-05T10:30:00.000Z",
            correlationId="corr-1",
            producer="payment",
            schemaVersion=1,
            payload={},
            causationId="evt-1",
        )
        calls: list[str] = []

        def handler(received: EventEnvelope) -> HandlerResult:
            calls.append(received.eventId)
            return HandlerResult.success()

        fields = {"envelope": json.dumps(envelope.as_dict())}
        subscriber._process_entry("events:payment", "reporting", "1-0", fields, handler, 1)
        subscriber._process_entry("events:payment", "reporting", "1-1", fields, handler, 1)
        self.assertEqual(calls, ["evt-redis-duplicate"])
        self.assertEqual(acks, ["1-0", "1-1"])

class PostgresProjectionTest(unittest.TestCase):
    def _projection_service(self) -> tuple[object, object]:
        from reporting.adapters.storage.postgres import PostgresReportingApplicationService

        class FakeCursor:
            def __init__(self, row: object | None = None, rows: list[tuple[object, ...]] | None = None) -> None:
                self._row = row
                self._rows = rows or []

            def fetchone(self) -> object | None:
                return self._row

            def fetchall(self) -> list[tuple[object, ...]]:
                return self._rows

        class FakeTransaction:
            def __enter__(self) -> None:
                return None

            def __exit__(self, *args: object) -> None:
                return None

        class FakeConnection:
            def __init__(self) -> None:
                self.metric_events: list[tuple[object, ...]] = []
                self.outbox: list[tuple[str, dict[str, object]]] = []
                self.processed: set[str] = set()
                self.refreshed: list[str] = []

            def __enter__(self) -> "FakeConnection":
                return self

            def __exit__(self, *args: object) -> None:
                return None

            def transaction(self) -> FakeTransaction:
                return FakeTransaction()

            def execute(self, query: str, params: tuple[object, ...] = ()) -> FakeCursor:
                normalized = " ".join(query.split())
                if normalized.startswith("SELECT version, data FROM"):
                    return FakeCursor()
                if normalized.startswith("INSERT INTO metric_definition_snapshots") or normalized.startswith("INSERT INTO dashboard_read_model_snapshots"):
                    return FakeCursor((1,))
                if normalized.startswith("INSERT INTO reporting_rebuild_runs"):
                    return FakeCursor()
                if normalized.startswith("INSERT INTO processed_events"):
                    if str(params[0]) in self.processed:
                        return FakeCursor()
                    self.processed.add(str(params[0]))
                    return FakeCursor((params[0],))
                if normalized.startswith("INSERT INTO reporting_metric_events"):
                    self.metric_events.append(params)
                    return FakeCursor()
                if normalized.startswith("REFRESH MATERIALIZED VIEW"):
                    self.refreshed.append(normalized.removeprefix("REFRESH MATERIALIZED VIEW "))
                    return FakeCursor()
                if normalized.startswith("SELECT event_id, event_type, occurred_at"):
                    return FakeCursor(rows=[self._metric_row(params) for params in self.metric_events])
                if normalized.startswith("UPDATE reporting_anomalies SET resolved_at"):
                    return FakeCursor()
                if normalized.startswith("INSERT INTO reporting_anomalies"):
                    return FakeCursor((params[0],))
                if normalized.startswith("INSERT INTO outbox"):
                    payload = params[2]
                    if hasattr(payload, "obj"):
                        payload = payload.obj
                    elif not isinstance(payload, dict):
                        payload = json.loads(payload)
                    self.outbox.append((str(params[1]), payload))
                    return FakeCursor()
                if normalized.startswith("SELECT id, version, data FROM dashboard_read_model_snapshots"):
                    return FakeCursor(rows=[])
                raise AssertionError(f"unexpected SQL: {normalized}")

            @staticmethod
            def _metric_row(params: tuple[object, ...]) -> tuple[object, ...]:
                return (
                    params[0], params[1], params[2], params[3], params[4], params[5],
                    params[6], params[7], params[8], params[9], params[10], params[11],
                    params[12], params[13],
                )

        class FakePool:
            def __init__(self) -> None:
                self.conn = FakeConnection()

            def connection(self) -> FakeConnection:
                return self.conn

        pool = FakePool()
        service = PostgresReportingApplicationService(pool)
        return pool, service

    def test_postgres_projection_persists_metric_events_and_publishes_urgent_action(self) -> None:
        pool, service = self._projection_service()
        skipped = service.handle_event(EventEnvelope(
            eventId="evt-pg-seat-skip",
            eventType="SeatAllocated",
            occurredAt="2026-07-05T10:30:00.000Z",
            correlationId="corr-1",
            producer="seat-assignment",
            schemaVersion=1,
            payload={"seatAllocationId": "salloc-1"},
            causationId="evt-source",
        ))
        self.assertEqual(skipped.status.value, "SUCCESS")
        self.assertEqual(pool.conn.processed, set())
        self.assertEqual(pool.conn.metric_events, [])

        occurred_at = datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
        for index in range(8):
            service.handle_event(EventEnvelope(eventId=f"evt-pg-pay-{index}", eventType="PaymentCaptured", occurredAt=occurred_at, correlationId="corr-1", producer="payment", schemaVersion=1, payload={"amount": "10.00", "currency": "USD"}, causationId="evt-source"))
        for index in range(2):
            service.handle_event(EventEnvelope(eventId=f"evt-pg-fail-{index}", eventType="PaymentFailed", occurredAt=occurred_at, correlationId="corr-1", producer="payment", schemaVersion=1, payload={}, causationId="evt-source"))

        event_types = [event[1] for event in pool.conn.metric_events]
        outbox_types = [envelope["eventType"] for _, envelope in pool.conn.outbox]

        self.assertEqual(event_types.count("PaymentCaptured"), 8)
        self.assertEqual(event_types.count("PaymentFailed"), 2)
        self.assertIn("reporting_revenue_by_route", pool.conn.refreshed)
        self.assertIn("reporting_revenue_breakdowns", pool.conn.refreshed)
        # Only payment captures feed the revenue views, so the two PaymentFailed
        # events must not trigger a refresh: each one takes an ACCESS EXCLUSIVE
        # lock that serialises every other reader of the table.
        self.assertEqual(pool.conn.refreshed.count("reporting_revenue_by_route"), 8)
        self.assertEqual(pool.conn.refreshed.count("reporting_revenue_breakdowns"), 8)
        self.assertIn("AnomalyDetected", outbox_types)
        self.assertIn("UrgentNotificationRequested", outbox_types)


if __name__ == "__main__":
    unittest.main()
