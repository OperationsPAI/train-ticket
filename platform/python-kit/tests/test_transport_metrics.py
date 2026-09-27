"""Process-level transport metrics for the Python kit.

These are the same event-consumer counters and outbox instruments the Java
shared platform publishes, so one recording rule and one dashboard read every
runtime. Two things have to hold for that: the instruments arrive under the exact
names, units and labels of the contract, and one message's path through the
subscriber (or one relay pass) produces the disposition combination the contract
describes. Both are pinned here, on a private meter provider so each test's
numbers are its own.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader

from train_ticket_platform import messaging, storage
from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.messaging import (
    FatalHandlerError,
    RedisEventSubscriber,
    TransientHandlerError,
)
from train_ticket_platform.metrics import (
    EventConsumerFailureType,
    EventConsumerMetrics,
    EventConsumerOutcome,
    OutboxFailureStage,
    OutboxMetrics,
    event_consumer_metrics,
    outbox_metrics,
)
from train_ticket_platform.storage import OutboxRelay

MESSAGES = "train_ticket.event.consumer.messages"
FAILURES = "train_ticket.event.consumer.failures"
DEAD_LETTERS = "train_ticket.event.consumer.dead_letters"
SAMPLE = "train_ticket.outbox.unpublished.sample"
OLDEST_AGE = "train_ticket.outbox.oldest_unpublished.age"
PUBLISHED = "train_ticket.outbox.published"
OUTBOX_FAILURES = "train_ticket.outbox.failures"


@pytest.fixture
def transport_meter() -> Any:
    """A meter provider of this test's own, so its counters start at zero.

    The kit's process-wide provider can only be installed once per interpreter
    and its reader is cumulative, so a module-scoped one would turn every
    assertion into a delta against whatever earlier tests recorded.
    """
    reader = InMemoryMetricReader()
    provider = MeterProvider(metric_readers=[reader])
    try:
        yield provider.get_meter("python-kit-test"), reader
    finally:
        provider.shutdown()


def _collected(reader: InMemoryMetricReader) -> dict[str, Any]:
    by_name: dict[str, Any] = {}
    for resource_metric in reader.get_metrics_data().resource_metrics:
        for scope_metric in resource_metric.scope_metrics:
            for metric in scope_metric.metrics:
                by_name[metric.name] = metric
    return by_name


def _points(metric: Any) -> dict[tuple[tuple[str, str], ...], Any]:
    return {
        tuple(sorted(point.attributes.items())): point.value
        for point in metric.data.data_points
    }


def _points_of(collected: dict[str, Any], name: str) -> dict[tuple[tuple[str, str], ...], Any]:
    # A counter nothing recorded has no data points at all, which is the same
    # statement as an empty mapping and easier to assert against.
    metric = collected.get(name)
    return {} if metric is None else _points(metric)


class FakeRedis:
    """The three commands the subscriber's delivery paths issue."""

    def __init__(self, delivery_count: int = 1) -> None:
        self.acks: list[tuple[str, str, str]] = []
        self.dlq_entries: list[tuple[str, dict[str, str]]] = []
        self.delivery_count = delivery_count
        self.dlq_write_error: Exception | None = None

    def xpending_range(self, stream: str, group: str, min: str, max: str, count: int) -> list[dict[str, int]]:
        return [{"times_delivered": self.delivery_count}]

    def xadd(self, stream: str, fields: dict[str, str], maxlen: int, approximate: bool) -> None:
        if self.dlq_write_error is not None:
            raise self.dlq_write_error
        self.dlq_entries.append((stream, fields))

    def xack(self, stream: str, group: str, msg_id: str) -> None:
        self.acks.append((stream, group, msg_id))


def _envelope() -> EventEnvelope:
    return EventEnvelope(eventType="SomethingHappened", producer="tester", payload={"x": 1})


def _fields(envelope: EventEnvelope) -> dict[str, str]:
    return {"envelope": json.dumps(envelope.to_json_dict())}


def _subscriber(metrics: EventConsumerMetrics, client: FakeRedis | None = None) -> RedisEventSubscriber:
    # The same shape the kit's messaging tests use: a subscriber with its
    # transport and its instruments supplied rather than constructed.
    subscriber = object.__new__(RedisEventSubscriber)
    subscriber._client = client or FakeRedis()
    subscriber._dedup = set()
    subscriber._dedup_lock = None
    subscriber._metrics = metrics
    return subscriber


def test_event_consumer_instruments_carry_the_shared_contract(transport_meter) -> None:
    meter, reader = transport_meter
    metrics = EventConsumerMetrics(meter)

    metrics.record_outcome(EventConsumerOutcome.SUCCESS)
    metrics.record_outcome(EventConsumerOutcome.DUPLICATE)
    metrics.record_outcome(EventConsumerOutcome.RETRY)
    metrics.record_outcome(EventConsumerOutcome.DEAD_LETTER)
    metrics.record_failure(EventConsumerFailureType.HANDLER_EXCEPTION)
    metrics.record_dead_letter(EventConsumerFailureType.TRANSIENT_RESULT)

    collected = _collected(reader)
    messages = collected[MESSAGES]
    assert messages.unit == "{message}"
    assert messages.description == "Event deliveries by their processing disposition."
    assert _points(messages) == {
        (("outcome", "success"),): 1,
        (("outcome", "duplicate"),): 1,
        (("outcome", "retry"),): 1,
        (("outcome", "dead_letter"),): 1,
    }

    failures = collected[FAILURES]
    assert failures.unit == "{failure}"
    assert failures.description == "Event-consumer failures by a bounded failure classification."
    assert _points(failures) == {(("failure.type", "handler_exception"),): 1}

    dead_letters = collected[DEAD_LETTERS]
    assert dead_letters.unit == "{message}"
    assert dead_letters.description == "Messages successfully handed to a dead-letter stream."
    assert _points(dead_letters) == {(("reason", "transient_result"),): 1}


def test_outbox_instruments_carry_the_shared_contract(transport_meter) -> None:
    meter, reader = transport_meter
    metrics = OutboxMetrics(meter)

    metrics.observe_unpublished_sample(3, 1.5)
    metrics.record_published(2)
    metrics.record_failure(OutboxFailureStage.SELECT)
    metrics.record_failure(OutboxFailureStage.PUBLISH)
    metrics.record_failure(OutboxFailureStage.MARK_PUBLISHED)

    collected = _collected(reader)
    sample = collected[SAMPLE]
    assert sample.unit == "{event}"
    assert sample.description == "Rows visible in the relay's bounded unpublished head sample."
    assert _points(sample) == {(): 3}
    # A row count, so an integer gauge rather than a float one.
    assert type(_points(sample)[()]) is int

    oldest = collected[OLDEST_AGE]
    assert oldest.unit == "s"
    assert oldest.description == "Age of the head-of-line unpublished outbox event."
    # Seconds, not milliseconds: the Java instrument publishes the same unit.
    assert _points(oldest) == {(): 1.5}
    assert type(_points(oldest)[()]) is float

    published = collected[PUBLISHED]
    assert published.unit == "{event}"
    assert published.description == "Outbox events whose bounded Redis publish pipeline completed successfully."
    assert _points(published) == {(): 2}

    failures = collected[OUTBOX_FAILURES]
    assert failures.unit == "{failure}"
    assert failures.description == "Outbox relay failures by a bounded processing stage."
    assert _points(failures) == {
        (("stage", "select"),): 1,
        (("stage", "publish"),): 1,
        (("stage", "mark_published"),): 1,
    }


def test_transport_instruments_are_no_ops_without_a_meter(monkeypatch) -> None:
    # A service started without an exporter must register nothing and must not
    # raise from a recording call.
    monkeypatch.setenv("OTEL_METRICS_EXPORTER", "none")
    consumer = event_consumer_metrics()
    consumer.record_outcome(EventConsumerOutcome.SUCCESS)
    consumer.record_failure(EventConsumerFailureType.HANDLER_EXCEPTION)
    consumer.record_dead_letter(EventConsumerFailureType.FATAL_RESULT)
    relay = outbox_metrics()
    relay.observe_unpublished_sample(5, 2.0)
    relay.record_published(3)
    relay.record_failure(OutboxFailureStage.SELECT)

    assert (consumer._messages, consumer._failures, consumer._dead_letters) == (None, None, None)
    assert (relay._published, relay._failures) == (None, None)


def test_the_subscriber_and_the_relay_resolve_the_process_instruments(monkeypatch, transport_meter) -> None:
    # The recording paths are proven above with instruments supplied; this pins
    # the other half, that a subscriber and a relay built by a service reach the
    # kit's own factory plus the process meter rather than recording nowhere.
    meter, reader = transport_meter
    monkeypatch.setattr(messaging, "event_consumer_metrics", lambda *_, **__: EventConsumerMetrics(meter))
    monkeypatch.setattr(storage, "outbox_metrics", lambda *_, **__: OutboxMetrics(meter))

    subscriber = object.__new__(RedisEventSubscriber)
    subscriber._client = FakeRedis()
    subscriber._dedup = set()
    subscriber._dedup_lock = None
    assert subscriber._transport_metrics() is subscriber._transport_metrics()
    subscriber._process_message(
        "events:tester", "tester", "tester-consumer", "1-0", _fields(_envelope()), lambda _: None
    )

    relay = object.__new__(OutboxRelay)
    relay._pool = FakeRelayPool([_row(1, "events:tester", 1.0)])
    relay._redis = FakeRelayRedis()
    assert relay._transport_metrics() is relay._transport_metrics()
    relay.relay_once(limit=1)

    collected = _collected(reader)
    assert _points_of(collected, MESSAGES) == {(("outcome", "success"),): 1}
    assert _points_of(collected, PUBLISHED) == {(): 1}
    assert _points_of(collected, SAMPLE) == {(): 1}


def test_a_delivered_message_records_one_success_outcome(transport_meter) -> None:
    meter, reader = transport_meter
    subscriber = _subscriber(EventConsumerMetrics(meter))

    subscriber._process_message(
        "events:tester", "tester", "tester-consumer", "1-0", _fields(_envelope()), lambda _: None
    )

    collected = _collected(reader)
    assert _points_of(collected, MESSAGES) == {(("outcome", "success"),): 1}
    assert _points_of(collected, FAILURES) == {}
    assert _points_of(collected, DEAD_LETTERS) == {}
    assert subscriber._client.acks == [("events:tester", "tester", "1-0")]


def test_a_redelivered_message_records_duplicate(transport_meter) -> None:
    meter, reader = transport_meter
    subscriber = _subscriber(EventConsumerMetrics(meter))
    envelope = _envelope()

    subscriber._process_message("events:tester", "tester", "tester-consumer", "1-0", _fields(envelope), lambda _: None)
    subscriber._process_message("events:tester", "tester", "tester-consumer", "1-1", _fields(envelope), lambda _: None)

    # The duplicate is a disposition of its own: this group already processed the
    # event, so the handler is not asked a second time.
    assert _points_of(_collected(reader), MESSAGES) == {
        (("outcome", "success"),): 1,
        (("outcome", "duplicate"),): 1,
    }


def test_a_transient_failure_below_the_limit_records_a_retry(transport_meter) -> None:
    meter, reader = transport_meter
    subscriber = _subscriber(EventConsumerMetrics(meter))

    def handler(_: EventEnvelope) -> None:
        raise TransientHandlerError("redis unavailable")

    subscriber._process_message("events:tester", "tester", "tester-consumer", "1-0", _fields(_envelope()), handler)

    collected = _collected(reader)
    assert _points_of(collected, FAILURES) == {(("failure.type", "transient_result"),): 1}
    assert _points_of(collected, MESSAGES) == {(("outcome", "retry"),): 1}
    assert _points_of(collected, DEAD_LETTERS) == {}
    # Left pending for Redis to redeliver: no DLQ record and no ack.
    assert subscriber._client.dlq_entries == []
    assert subscriber._client.acks == []


def test_a_transient_failure_at_the_give_up_limit_records_a_dead_letter(transport_meter) -> None:
    meter, reader = transport_meter
    subscriber = _subscriber(EventConsumerMetrics(meter))
    envelope = _envelope()

    def handler(_: EventEnvelope) -> None:
        raise TransientHandlerError("redis unavailable")

    # Redis hands the same entry back after each failure, so the entry id is the
    # same on every attempt.
    for _ in range(messaging.MAX_DELIVERY_ATTEMPTS):
        subscriber._process_message("events:tester", "tester", "tester-consumer", "1-0", _fields(envelope), handler)

    collected = _collected(reader)
    assert _points_of(collected, FAILURES) == {
        (("failure.type", "transient_result"),): messaging.MAX_DELIVERY_ATTEMPTS
    }
    assert _points_of(collected, MESSAGES) == {
        (("outcome", "retry"),): messaging.MAX_DELIVERY_ATTEMPTS - 1,
        (("outcome", "dead_letter"),): 1,
    }
    assert _points_of(collected, DEAD_LETTERS) == {(("reason", "transient_result"),): 1}
    assert len(subscriber._client.dlq_entries) == 1
    assert subscriber._client.acks == [("events:tester", "tester", "1-0")]


def test_an_unhandled_handler_exception_records_a_handler_failure(transport_meter) -> None:
    meter, reader = transport_meter
    subscriber = _subscriber(EventConsumerMetrics(meter))

    def handler(_: EventEnvelope) -> None:
        raise RuntimeError("database is down")

    subscriber._process_message("events:tester", "tester", "tester-consumer", "1-0", _fields(_envelope()), handler)

    collected = _collected(reader)
    assert _points_of(collected, FAILURES) == {(("failure.type", "handler_exception"),): 1}
    assert _points_of(collected, MESSAGES) == {(("outcome", "retry"),): 1}


def test_a_fatal_handler_error_records_a_fatal_failure_and_dead_letter(transport_meter) -> None:
    meter, reader = transport_meter
    subscriber = _subscriber(EventConsumerMetrics(meter))

    def handler(_: EventEnvelope) -> None:
        raise FatalHandlerError("poison message")

    subscriber._process_message("events:tester", "tester", "tester-consumer", "1-0", _fields(_envelope()), handler)

    collected = _collected(reader)
    # A fatal signal is not a retryable handler crash, so it is never counted as
    # one, and it is not retried.
    assert _points_of(collected, FAILURES) == {(("failure.type", "fatal_result"),): 1}
    assert _points_of(collected, MESSAGES) == {(("outcome", "dead_letter"),): 1}
    assert _points_of(collected, DEAD_LETTERS) == {(("reason", "fatal_result"),): 1}
    assert subscriber._client.acks == [("events:tester", "tester", "1-0")]


def test_an_unparseable_envelope_is_dead_lettered_as_invalid(transport_meter) -> None:
    meter, reader = transport_meter
    subscriber = _subscriber(EventConsumerMetrics(meter))

    subscriber._process_message(
        "events:tester", "tester", "tester-consumer", "1-0", {"envelope": "{not json"}, lambda _: None
    )

    collected = _collected(reader)
    assert _points_of(collected, FAILURES) == {(("failure.type", "invalid_envelope"),): 1}
    assert _points_of(collected, MESSAGES) == {(("outcome", "dead_letter"),): 1}
    assert _points_of(collected, DEAD_LETTERS) == {(("reason", "invalid_envelope"),): 1}
    assert subscriber._client.dlq_entries[0][1]["failureReason"].startswith("JSONDecodeError")


def test_a_missing_envelope_field_is_dead_lettered_as_missing(transport_meter) -> None:
    meter, reader = transport_meter
    subscriber = _subscriber(EventConsumerMetrics(meter))

    subscriber._process_message("events:tester", "tester", "tester-consumer", "1-0", {}, lambda _: None)

    collected = _collected(reader)
    # A different integration fault from an envelope that will not parse, and
    # classified apart from it for that reason.
    assert _points_of(collected, FAILURES) == {(("failure.type", "missing_envelope"),): 1}
    assert _points_of(collected, MESSAGES) == {(("outcome", "dead_letter"),): 1}
    assert _points_of(collected, DEAD_LETTERS) == {(("reason", "missing_envelope"),): 1}
    assert subscriber._client.dlq_entries[0][1]["failureReason"] == "MissingEnvelope"


def test_a_failed_dlq_write_records_a_write_failure(transport_meter) -> None:
    meter, reader = transport_meter
    client = FakeRedis()
    client.dlq_write_error = RuntimeError("redis is unavailable")
    subscriber = _subscriber(EventConsumerMetrics(meter), client)

    def handler(_: EventEnvelope) -> None:
        raise FatalHandlerError("poison message")

    with pytest.raises(RuntimeError, match="redis is unavailable"):
        subscriber._process_message("events:tester", "tester", "tester-consumer", "1-0", _fields(_envelope()), handler)

    collected = _collected(reader)
    # The hand-off did not happen, so nothing is counted as dead-lettered and the
    # entry stays pending for a later delivery to retry the write. The failure
    # that asked for the hand-off is still counted, and the failed write is
    # counted beside it rather than instead of it.
    assert _points_of(collected, FAILURES) == {
        (("failure.type", "fatal_result"),): 1,
        (("failure.type", "dlq_write"),): 1,
    }
    assert _points_of(collected, MESSAGES) == {}
    assert _points_of(collected, DEAD_LETTERS) == {}
    assert subscriber._client.acks == []


def test_a_redelivered_dead_letter_hand_off_is_not_counted_twice(transport_meter) -> None:
    meter, reader = transport_meter
    subscriber = _subscriber(EventConsumerMetrics(meter))
    envelope = _envelope()

    def handler(_: EventEnvelope) -> None:
        raise FatalHandlerError("poison message")

    # A crash between the DLQ write and the ack re-delivers the entry, and one
    # message must still be one dead letter rather than two.
    for _ in range(2):
        subscriber._process_message("events:tester", "tester", "tester-consumer", "1-0", _fields(envelope), handler)

    collected = _collected(reader)
    assert _points_of(collected, MESSAGES) == {(("outcome", "dead_letter"),): 1}
    assert _points_of(collected, DEAD_LETTERS) == {(("reason", "fatal_result"),): 1}
    assert len(subscriber._client.dlq_entries) == 1


class FakePipeline:
    def __init__(self, published: list[tuple[str, dict[str, str]]], error: Exception | None) -> None:
        self._published = published
        self._error = error
        self._pending: list[tuple[str, dict[str, str]]] = []

    def __enter__(self) -> "FakePipeline":
        return self

    def __exit__(self, *_: Any) -> None:
        return None

    def xadd(self, stream: str, fields: dict[str, str], maxlen: int, approximate: bool) -> None:
        self._pending.append((stream, fields))

    def execute(self) -> None:
        if self._error is not None:
            raise self._error
        self._published.extend(self._pending)


class FakeRelayRedis:
    def __init__(self, error: Exception | None = None) -> None:
        self.published: list[tuple[str, dict[str, str]]] = []
        self.error = error

    def pipeline(self, transaction: bool = False) -> FakePipeline:
        return FakePipeline(self.published, self.error)


class FakeCursor:
    def __init__(self, rows: list[tuple[Any, ...]]) -> None:
        self._rows = rows

    def fetchall(self) -> list[tuple[Any, ...]]:
        return self._rows


class FakeRelayConnection:
    def __init__(self, rows: list[tuple[Any, ...]], failures: dict[str, Exception]) -> None:
        self._rows = rows
        self._failures = failures
        self.marked: list[Any] = []
        self.committed = False

    def execute(self, sql: str, params: Any = None) -> FakeCursor:
        stage = "select" if sql.lstrip().upper().startswith("SELECT") else "mark_published"
        if stage in self._failures:
            raise self._failures[stage]
        if stage == "select":
            return FakeCursor(self._rows)
        self.marked.extend(params[0] if params else [])
        return FakeCursor([])

    def commit(self) -> None:
        self.committed = True

    def __enter__(self) -> "FakeRelayConnection":
        return self

    def __exit__(self, *_: Any) -> None:
        return None


class FakeRelayPool:
    def __init__(self, rows: list[tuple[Any, ...]], failures: dict[str, Exception] | None = None) -> None:
        self.connection_returned = FakeRelayConnection(rows, failures or {})

    def connection(self) -> FakeRelayConnection:
        return self.connection_returned


def _relay(
    metrics: OutboxMetrics,
    rows: list[tuple[Any, ...]],
    *,
    redis_error: Exception | None = None,
    failures: dict[str, Exception] | None = None,
) -> tuple[OutboxRelay, FakeRelayRedis]:
    relay = object.__new__(OutboxRelay)
    relay._pool = FakeRelayPool(rows, failures)
    relay._redis = FakeRelayRedis(redis_error)
    relay._metrics = metrics
    return relay, relay._redis


def _row(seq: int, stream: str, age_seconds: float) -> tuple[Any, ...]:
    return (
        seq,
        stream,
        json.dumps({"eventId": f"evt-{seq}", "eventType": "SomethingHappened", "producer": "tester"}),
        datetime.now(UTC) - timedelta(seconds=age_seconds),
    )


def test_one_relay_pass_records_the_bounded_head_sample_and_published_rows(transport_meter) -> None:
    meter, reader = transport_meter
    relay, redis = _relay(
        OutboxMetrics(meter),
        [_row(1, "events:tester", 3.0), _row(2, "events:tester", 1.0)],
    )

    assert relay.relay_once(limit=2) == 2

    collected = _collected(reader)
    assert _points_of(collected, PUBLISHED) == {(): 2}
    assert _points_of(collected, OUTBOX_FAILURES) == {}
    # The sample is the batch the relay actually read, and the age is the head of
    # line's rather than the newest row's.
    assert _points_of(collected, SAMPLE) == {(): 2}
    (age,) = _points(collected[OLDEST_AGE]).values()
    assert 3.0 <= age < 4.0
    assert [stream for stream, _ in redis.published] == ["events:tester", "events:tester"]


def test_an_empty_relay_pass_reports_a_zero_sample(transport_meter) -> None:
    meter, reader = transport_meter
    relay, _ = _relay(OutboxMetrics(meter), [])

    assert relay.relay_once(limit=2) == 0

    collected = _collected(reader)
    assert _points_of(collected, SAMPLE) == {(): 0}
    assert _points_of(collected, PUBLISHED) == {}


def test_each_relay_failure_stage_is_counted_apart(transport_meter) -> None:
    meter, reader = transport_meter
    relay, _ = _relay(
        OutboxMetrics(meter),
        [_row(1, "events:tester", 1.0)],
        failures={"select": RuntimeError("postgres is down")},
    )
    with pytest.raises(RuntimeError, match="postgres is down"):
        relay.relay_once(limit=1)
    assert _points_of(_collected(reader), OUTBOX_FAILURES) == {(("stage", "select"),): 1}

    relay, _ = _relay(
        OutboxMetrics(meter),
        [_row(1, "events:tester", 1.0)],
        redis_error=RuntimeError("redis is down"),
    )
    with pytest.raises(RuntimeError, match="redis is down"):
        relay.relay_once(limit=1)
    assert _points_of(_collected(reader), OUTBOX_FAILURES) == {
        (("stage", "select"),): 1,
        (("stage", "publish"),): 1,
    }

    # The rows are on the stream by then, so a failed mark means a second
    # publication rather than a lost event, and it is reported separately.
    relay, _ = _relay(
        OutboxMetrics(meter),
        [_row(1, "events:tester", 1.0)],
        failures={"mark_published": RuntimeError("update failed")},
    )
    with pytest.raises(RuntimeError, match="update failed"):
        relay.relay_once(limit=1)
    assert _points_of(_collected(reader), OUTBOX_FAILURES) == {
        (("stage", "select"),): 1,
        (("stage", "publish"),): 1,
        (("stage", "mark_published"),): 1,
    }
