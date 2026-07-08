import json
import logging

from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.messaging import FatalHandlerError, HandlerResult, InMemoryEventSubscriber, RedisEventSubscriber, TransientHandlerError, dlq_for_stream


class FakeRedis:
    def __init__(self) -> None:
        self.acks: list[tuple[str, str, str]] = []
        self.dlq_entries: list[tuple[str, dict[str, str]]] = []
        self.delivery_count = 1

    def xpending_range(self, stream: str, group: str, min: str, max: str, count: int) -> list[dict[str, int]]:
        return [{"times_delivered": self.delivery_count}]

    def xadd(self, stream: str, fields: dict[str, str], maxlen: int, approximate: bool) -> None:
        self.dlq_entries.append((stream, fields))

    def xack(self, stream: str, group: str, msg_id: str) -> None:
        self.acks.append((stream, group, msg_id))


def test_unexpected_handler_exception_uses_dlq_policy_without_escaping(caplog) -> None:
    caplog.set_level(logging.WARNING)
    subscriber = object.__new__(RedisEventSubscriber)
    subscriber._client = FakeRedis()
    subscriber._dedup = set()
    subscriber._dedup_lock = None
    envelope = EventEnvelope(eventType="SomethingHappened", producer="tester", payload={"x": 1})
    fields = {"envelope": json.dumps(envelope.to_json_dict())}

    def handler(_: EventEnvelope) -> None:
        raise FatalHandlerError("poison message")

    subscriber._process_message("events:tester", "tester", "tester-consumer", "1-0", fields, handler)

    assert subscriber._client.acks == [("events:tester", "tester", "1-0")]
    assert subscriber._client.dlq_entries[0][0] == dlq_for_stream("events:tester")
    fields = subscriber._client.dlq_entries[0][1]
    assert fields["consumerGroup"] == "tester"
    assert fields["consumerName"] == "tester-consumer"
    assert fields["failureReason"] == "FatalHandlerError: poison message"
    assert fields["attempts"] == "1"
    assert fields["deadLetteredAt"].endswith("Z")
    assert "moving message to DLQ" in caplog.text


def test_process_entry_uses_supplied_consumer_name_for_max_delivery_dlq() -> None:
    subscriber = object.__new__(RedisEventSubscriber)
    subscriber._client = FakeRedis()
    subscriber._dedup = set()
    subscriber._dedup_lock = None
    envelope = EventEnvelope(eventType="SomethingHappened", producer="tester", payload={"x": 1})
    fields = {"envelope": json.dumps(envelope.to_json_dict())}

    subscriber._process_entry(
        "events:tester",
        "tester",
        "1-0",
        fields,
        lambda _: None,
        5,
        consumer_name="tester-consumer",
    )

    dlq_fields = subscriber._client.dlq_entries[0][1]
    assert dlq_fields["consumerName"] == "tester-consumer"


def test_transient_handler_exception_logs_retry_path(caplog) -> None:
    caplog.set_level(logging.WARNING)
    subscriber = object.__new__(RedisEventSubscriber)
    subscriber._client = FakeRedis()
    subscriber._dedup = set()
    subscriber._dedup_lock = None
    envelope = EventEnvelope(eventType="SomethingHappened", producer="tester", payload={"x": 1})
    fields = {"envelope": json.dumps(envelope.to_json_dict())}

    def handler(_: EventEnvelope) -> None:
        raise TransientHandlerError("redis unavailable")

    subscriber._process_message("events:tester", "tester", "tester-consumer", "1-0", fields, handler)

    assert subscriber._client.acks == []
    assert subscriber._client.dlq_entries == []
    assert "handler transient failure" in caplog.text
    assert "TransientHandlerError" in caplog.text
    assert envelope.eventId in caplog.text


def test_ack_skip_duplicate_logs_ack_path(caplog) -> None:
    caplog.set_level(logging.WARNING)
    subscriber = object.__new__(RedisEventSubscriber)
    subscriber._client = FakeRedis()
    subscriber._dedup = set()
    subscriber._dedup_lock = None
    envelope = EventEnvelope(eventType="SomethingHappened", producer="tester", payload={"x": 1})
    subscriber._record_seen(envelope.eventId)
    fields = {"envelope": json.dumps(envelope.to_json_dict())}

    subscriber._process_message("events:tester", "tester", "tester-consumer", "1-0", fields, lambda _: HandlerResult.success())

    assert subscriber._client.acks == [("events:tester", "tester", "1-0")]
    assert "duplicate event already processed" in caplog.text
    assert envelope.eventId in caplog.text


def test_otel_absent_does_not_create_event_consumer_span(monkeypatch) -> None:
    monkeypatch.delenv("OTEL_TRACES_EXPORTER", raising=False)
    subscriber = InMemoryEventSubscriber([EventEnvelope(eventType="SomethingHappened", producer="tester", payload={})])
    subscriber.subscribe(["events:tester"], "tester", "tester-consumer", lambda _: HandlerResult.success())
    assert len(subscriber.seen_event_ids) == 1


def test_otel_enabled_creates_event_consumer_span(monkeypatch) -> None:
    pytest = __import__("pytest")
    trace = pytest.importorskip("opentelemetry.trace")
    exporter_mod = pytest.importorskip("opentelemetry.sdk.trace.export.in_memory_span_exporter")
    from train_ticket_platform.observability import init_opentelemetry

    monkeypatch.setenv("OTEL_TRACES_EXPORTER", "otlp")
    monkeypatch.setenv("OTEL_SERVICE_NAME", "python-kit-test")
    exporter = exporter_mod.InMemorySpanExporter()
    init_opentelemetry("python-kit-test", span_exporter=exporter)
    envelope = EventEnvelope(eventType="SomethingHappened", producer="tester", payload={})
    subscriber = InMemoryEventSubscriber([envelope])
    subscriber.subscribe(["events:tester"], "tester", "tester-consumer", lambda _: HandlerResult.success())

    spans = exporter.get_finished_spans()
    assert any(span.name == "in-memory process SomethingHappened" for span in spans)
    span = next(span for span in spans if span.name == "in-memory process SomethingHappened")
    assert span.attributes["messaging.train_ticket.stream"] == "in-memory"
    assert span.attributes["messaging.train_ticket.consumerGroup"] == "in-memory"
    assert span.attributes["messaging.train_ticket.eventId"] == envelope.eventId
    assert span.attributes["messaging.train_ticket.eventType"] == envelope.eventType
    assert span.attributes["messaging.train_ticket.correlationId"] == envelope.correlationId
    trace.get_tracer_provider().shutdown()


def test_otel_enabled_creates_http_server_span(monkeypatch) -> None:
    pytest = __import__("pytest")
    otel_trace = pytest.importorskip("opentelemetry.trace")
    exporter_mod = pytest.importorskip("opentelemetry.sdk.trace.export.in_memory_span_exporter")
    fastapi = pytest.importorskip("fastapi")
    testclient = pytest.importorskip("fastapi.testclient")
    from train_ticket_platform.observability import init_opentelemetry

    monkeypatch.setenv("OTEL_TRACES_EXPORTER", "otlp")
    monkeypatch.setenv("OTEL_SERVICE_NAME", "python-kit-test")
    exporter = exporter_mod.InMemorySpanExporter()
    app = fastapi.FastAPI()

    @app.get("/health")
    def health() -> dict[str, bool]:
        return {"ok": True}

    init_opentelemetry("python-kit-test", app=app, span_exporter=exporter)
    client = testclient.TestClient(app)
    response = client.get("/health")
    assert response.status_code == 200
    spans = exporter.get_finished_spans()
    server_spans = [span for span in spans if span.kind == otel_trace.SpanKind.SERVER]
    assert server_spans, f"expected an HTTP server span, got: {[span.name for span in spans]}"
    route_attr = server_spans[0].attributes.get("http.route") or server_spans[0].attributes.get("http.target")
    assert route_attr == "/health"
