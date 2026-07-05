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
from reporting.adapters.messaging.subscriber import RedisEventSubscriber
from reporting.domain import ReportingError


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


class MessagingTest(unittest.TestCase):
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
        self.assertEqual(maxlen, 100_000)
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


if __name__ == "__main__":
    unittest.main()
