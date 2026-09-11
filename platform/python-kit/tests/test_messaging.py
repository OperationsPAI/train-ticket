import json
import logging
import threading
from datetime import UTC, datetime

from train_ticket_platform import messaging
from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.messaging import FatalHandlerError, HandlerResult, InMemoryEventSubscriber, RedisEventPublisher, RedisEventSubscriber, TransientHandlerError, dlq_for_stream
from train_ticket_platform.observability import init_opentelemetry


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


def test_envelope_omits_trace_context_when_otel_disabled(monkeypatch) -> None:
    monkeypatch.delenv("OTEL_TRACES_EXPORTER", raising=False)
    envelope = EventEnvelope(
        eventId="evt-test",
        eventType="SomethingHappened",
        occurredAt=datetime(2026, 7, 8, tzinfo=UTC),
        correlationId="corr-test",
        causationId="cmd-test",
        producer="tester",
        payload={"x": 1},
    )

    assert envelope.to_json_dict() == {
        "eventId": "evt-test",
        "eventType": "SomethingHappened",
        "occurredAt": "2026-07-08T00:00:00.000Z",
        "correlationId": "corr-test",
        "producer": "tester",
        "schemaVersion": 1,
        "payload": {"x": 1},
        "causationId": "cmd-test",
    }


def test_envelope_deserialization_ignores_unknown_fields_and_preserves_trace_context(monkeypatch) -> None:
    monkeypatch.delenv("OTEL_TRACES_EXPORTER", raising=False)
    restored = EventEnvelope.from_json_dict({
        "eventId": "evt-test",
        "eventType": "SomethingHappened",
        "occurredAt": "2026-07-08T00:00:00.000Z",
        "correlationId": "corr-test",
        "producer": "tester",
        "schemaVersion": 1,
        "payload": {},
        "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        "tracestate": "vendor=value",
        "futureField": "ignored",
    })

    assert restored.traceparent == "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    assert restored.tracestate == "vendor=value"
    assert restored.to_json_dict()["traceparent"] == restored.traceparent


def test_otel_enabled_injects_trace_context_and_consumer_uses_remote_parent(monkeypatch) -> None:
    pytest = __import__("pytest")
    trace = pytest.importorskip("opentelemetry.trace")
    exporter_mod = pytest.importorskip("opentelemetry.sdk.trace.export.in_memory_span_exporter")

    monkeypatch.setenv("OTEL_TRACES_EXPORTER", "otlp")
    monkeypatch.setenv("OTEL_SERVICE_NAME", "python-kit-trace-test")
    exporter = exporter_mod.InMemorySpanExporter()
    provider = init_opentelemetry("python-kit-trace-test", span_exporter=exporter)
    producer_span = None
    with trace.get_tracer("python-kit-trace-test").start_as_current_span("producer") as span:
        producer_span = span
        envelope = EventEnvelope(eventType="SomethingHappened", producer="tester", payload={})
    subscriber = InMemoryEventSubscriber([envelope])
    subscriber.subscribe(["events:tester"], "tester", "tester-consumer", lambda _: HandlerResult.success())

    consumer = next(span for span in exporter.get_finished_spans() if span.name == "in-memory process SomethingHappened")
    assert envelope.traceparent is not None
    assert consumer.context.trace_id == producer_span.get_span_context().trace_id
    assert consumer.parent.span_id == producer_span.get_span_context().span_id
    provider.shutdown()


def test_malformed_traceparent_is_ignored_for_consumer_parent(monkeypatch) -> None:
    pytest = __import__("pytest")
    trace = pytest.importorskip("opentelemetry.trace")
    exporter_mod = pytest.importorskip("opentelemetry.sdk.trace.export.in_memory_span_exporter")

    monkeypatch.setenv("OTEL_TRACES_EXPORTER", "otlp")
    exporter = exporter_mod.InMemorySpanExporter()
    provider = init_opentelemetry("python-kit-trace-test", span_exporter=exporter)
    envelope = EventEnvelope(eventType="SomethingHappened", producer="tester", payload={}, traceparent="not-valid")
    subscriber = InMemoryEventSubscriber([envelope])
    subscriber.subscribe(["events:tester"], "tester", "tester-consumer", lambda _: HandlerResult.success())

    consumer = next(span for span in exporter.get_finished_spans() if span.name == "in-memory process SomethingHappened")
    assert not consumer.parent or not consumer.parent.is_valid
    provider.shutdown()

class CapturingRedis:
    """Records the exact kwargs each xadd receives, so assertions check the real
    command shape rather than trusting the code path."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict, dict]] = []
        self.acks: list[tuple[str, str, str]] = []
        self.delivery_count = 1

    def xpending_range(self, stream: str, group: str, min: str, max: str, count: int):
        return [{"times_delivered": self.delivery_count}]

    def xadd(self, stream, fields, **kwargs):
        self.calls.append((stream, fields, kwargs))

    def xack(self, stream: str, group: str, msg_id: str) -> None:
        self.acks.append((stream, group, msg_id))


def test_publish_xadd_carries_approximate_maxlen_at_configured_cap() -> None:
    publisher = object.__new__(RedisEventPublisher)
    publisher._client = CapturingRedis()
    envelope = EventEnvelope(eventType="SomethingHappened", producer="tester", payload={"x": 1})

    publisher.publish(envelope)

    stream, _, kwargs = publisher._client.calls[0]
    assert stream == "events:tester"
    assert kwargs["maxlen"] == messaging.MAXLEN
    assert kwargs["approximate"] is True


def test_dlq_xadd_carries_approximate_maxlen_at_configured_cap() -> None:
    subscriber = object.__new__(RedisEventSubscriber)
    subscriber._client = CapturingRedis()
    subscriber._dedup = set()
    subscriber._dedup_lock = None
    envelope = EventEnvelope(eventType="SomethingHappened", producer="tester", payload={"x": 1})
    fields = {"envelope": json.dumps(envelope.to_json_dict())}

    def handler(_: EventEnvelope) -> None:
        raise FatalHandlerError("poison message")

    subscriber._process_message("events:tester", "tester", "tester-consumer", "1-0", fields, handler)

    stream, _, kwargs = subscriber._client.calls[0]
    assert stream == dlq_for_stream("events:tester")
    assert kwargs["maxlen"] == messaging.MAXLEN
    assert kwargs["approximate"] is True


def test_outbox_relay_xadd_carries_approximate_maxlen_at_configured_cap() -> None:
    from train_ticket_platform.storage import OutboxRelay

    class Cursor:
        def fetchall(self):
            return [(1, "events:fare-pricing", {"eventId": "evt-1"})]

    class Conn:
        def __init__(self) -> None:
            self.commands = []

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb) -> None:
            return None

        def execute(self, sql, params=()):
            self.commands.append((sql, params))
            if sql.startswith("SELECT seq"):
                return Cursor()
            return None

        def commit(self) -> None:
            pass

    class Pool:
        def __init__(self, conn) -> None:
            self.conn = conn

        def connection(self):
            return self.conn

    redis_fake = CapturingRedis()
    OutboxRelay(Pool(Conn()), redis_client=redis_fake).relay_once()

    stream, _, kwargs = redis_fake.calls[0]
    assert stream == "events:fare-pricing"
    assert kwargs["maxlen"] == messaging.MAXLEN
    assert kwargs["approximate"] is True


def test_default_stream_maxlen_matches_java_kit() -> None:
    assert messaging.DEFAULT_STREAM_MAXLEN == 10000
    assert messaging.STREAM_MAXLEN_ENV == "EVENT_STREAM_MAXLEN"


def test_stream_maxlen_honours_env_override() -> None:
    assert messaging.stream_maxlen("2500") == 2500
    assert messaging.stream_maxlen(" 750 ") == 750


def test_stream_maxlen_falls_back_to_default_when_unset_or_invalid(caplog) -> None:
    caplog.set_level(logging.WARNING)
    default = messaging.DEFAULT_STREAM_MAXLEN

    assert messaging.stream_maxlen(None) == default
    assert messaging.stream_maxlen("  ") == default
    assert messaging.stream_maxlen("not-a-number") == default
    assert messaging.stream_maxlen("0") == default
    assert messaging.stream_maxlen("-5") == default

    assert "is not a positive integer" in caplog.text


class PruneTrackingRedis:
    """Drives subscribe() through a bounded number of poll iterations."""

    def __init__(self, subscriber: RedisEventSubscriber, iterations: int) -> None:
        self._subscriber = subscriber
        self._remaining = iterations
        self.xinfo_calls = 0
        self.deleted: list[str] = []

    def xgroup_create(self, stream, group, id, mkstream):
        return None

    def xinfo_consumers(self, stream: str, group: str):
        self.xinfo_calls += 1
        return [{"name": "reporting-old-pod", "idle": messaging.DEAD_CONSUMER_IDLE_MS + 1, "pending": 42}]

    def execute_command(self, *args):
        if args[0] == "XGROUP" and args[1] == "DELCONSUMER":
            self.deleted.append(args[4])

    def xautoclaim(self, *args, **kwargs):
        return ["0-0", []]

    def xreadgroup(self, *args, **kwargs):
        self._remaining -= 1
        if self._remaining <= 0:
            self._subscriber._stop_requested.set()
        return []


def test_dead_consumers_are_swept_periodically_not_only_at_startup() -> None:
    # A consumer that just crashed is by definition not yet idle, so a
    # startup-only sweep can never reclaim the pending entries of the process
    # this one replaced -- the one case that matters. The orphan then holds its
    # PEL forever.
    subscriber = object.__new__(RedisEventSubscriber)
    subscriber._stop_requested = threading.Event()
    subscriber._dedup = set()
    subscriber._dedup_lock = threading.Lock()
    subscriber._response_error_type = Exception
    redis = PruneTrackingRedis(subscriber, iterations=3)
    subscriber._client = redis

    interval = messaging.PRUNE_INTERVAL_MS / 1000
    clock = iter([n * interval for n in range(20)])
    real_monotonic = messaging.time.monotonic
    messaging.time.monotonic = lambda: next(clock)
    try:
        subscriber.subscribe(("events:payment",), "reporting", "reporting-this-pod", lambda _: None)
    finally:
        messaging.time.monotonic = real_monotonic

    # once before the loop, then once per elapsed interval inside it
    assert redis.xinfo_calls > 1
    assert redis.deleted == ["reporting-old-pod"] * redis.xinfo_calls
