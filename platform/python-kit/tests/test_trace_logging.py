"""Trace ids on log records.

The point of this module is that a log line can be joined to a trace. These
tests pin the two ways that silently stops being true: ids that never arrive,
and ids that get corrupted -- an all-zero id that looks real -- while a request
is still running.
"""

from __future__ import annotations

import logging

from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace import (
    INVALID_SPAN,
    NonRecordingSpan,
    SpanContext,
    TraceFlags,
    format_trace_id,
    use_span,
)

from train_ticket_platform.events import EventEnvelope, envelope_factory
from train_ticket_platform.messaging import HandlerResult, InMemoryEventSubscriber
from train_ticket_platform.observability import init_opentelemetry
from train_ticket_platform.trace_logging import (
    ABSENT,
    SPAN_ID_FIELD,
    TRACE_ID_FIELD,
    current_trace_logging_ids,
    install_trace_logging,
    trace_logging_log_format,
)

TRACE_INT = 0x0AF7651916CD43DD8448EB211C80319C
SPAN_INT = 0xB7AD6B7169203331
TRACE_HEX = "0af7651916cd43dd8448eb211c80319c"
SPAN_HEX = "b7ad6b7169203331"
ZERO_TRACE_HEX = "0" * 32


def _install_sdk() -> None:
    # get_current_span reads a contextvar the SDK's context manager writes; the
    # API's default provider never makes a span current, so a real provider must
    # back these tests or use_span would prove nothing.
    if not isinstance(trace.get_tracer_provider(), TracerProvider):
        provider = TracerProvider()
        provider.add_span_processor(SimpleSpanProcessor(InMemorySpanExporter()))
        trace.set_tracer_provider(provider)


def _sampled_span(trace_int: int = TRACE_INT, span_int: int = SPAN_INT) -> NonRecordingSpan:
    context = SpanContext(
        trace_id=trace_int,
        span_id=span_int,
        is_remote=False,
        trace_flags=TraceFlags(TraceFlags.SAMPLED),
    )
    return NonRecordingSpan(context)


def test_current_ids_carry_trace_and_span_for_a_valid_span() -> None:
    _install_sdk()
    with use_span(_sampled_span(), end_on_exit=False):
        assert current_trace_logging_ids() == {TRACE_ID_FIELD: TRACE_HEX, SPAN_ID_FIELD: SPAN_HEX}
    assert current_trace_logging_ids() == {}


def test_current_ids_are_empty_for_an_invalid_span() -> None:
    # An absent or non-recording span yields an all-zero trace id. Reporting that
    # is worse than reporting nothing: it looks like a real id and joins every
    # unrelated line in the log together.
    _install_sdk()
    with use_span(INVALID_SPAN, end_on_exit=False):
        assert current_trace_logging_ids() == {}


def test_nested_scope_restores_the_enclosing_ids() -> None:
    # The regression this guards: a handler runs inside a span, calls something
    # that enters a non-recording span, and must not keep the inner span's (or a
    # cleared) id for the rest of its work -- the lines most likely to explain a
    # failure, since they come after it.
    _install_sdk()
    inner = _sampled_span(0x4BF92F3577B34DA6A3CE929D0E0E4736, 0x00F067AA0BA902B7)
    with use_span(_sampled_span(), end_on_exit=False):
        assert current_trace_logging_ids()[TRACE_ID_FIELD] == TRACE_HEX
        with use_span(inner, end_on_exit=False):
            assert current_trace_logging_ids()[TRACE_ID_FIELD] == "4bf92f3577b34da6a3ce929d0e0e4736"
        assert current_trace_logging_ids()[TRACE_ID_FIELD] == TRACE_HEX


def test_inner_invalid_scope_does_not_leak_zeros_or_clear_the_outer() -> None:
    # The subtle one: while an inner non-recording span is current the record must
    # show ABSENT, not an all-zero id; and once it exits the outer span's id must
    # come back. Both halves matter -- zeros would join unrelated lines, a clear
    # would strand the rest of the handler.
    _install_sdk()
    with use_span(_sampled_span(), end_on_exit=False):
        with use_span(INVALID_SPAN, end_on_exit=False):
            assert current_trace_logging_ids() == {}
        assert current_trace_logging_ids()[TRACE_ID_FIELD] == TRACE_HEX


def test_current_ids_are_empty_with_no_active_span() -> None:
    assert current_trace_logging_ids() == {}


def test_factory_sets_absent_on_records_logged_outside_a_span() -> None:
    # Attributes are always present, so a format string naming them never raises
    # on a startup or shutdown line.
    install_trace_logging()
    record = logging.getLogRecordFactory()("x", logging.INFO, __file__, 1, "hi", None, None)
    assert getattr(record, TRACE_ID_FIELD) == ABSENT
    assert getattr(record, SPAN_ID_FIELD) == ABSENT


def test_factory_stamps_the_active_span_onto_a_record() -> None:
    _install_sdk()
    install_trace_logging()
    with use_span(_sampled_span(), end_on_exit=False):
        record = logging.getLogRecordFactory()("x", logging.INFO, __file__, 1, "hi", None, None)
    assert getattr(record, TRACE_ID_FIELD) == TRACE_HEX
    assert getattr(record, SPAN_ID_FIELD) == SPAN_HEX


def test_factory_never_stamps_the_all_zero_trace_id() -> None:
    _install_sdk()
    install_trace_logging()
    with use_span(INVALID_SPAN, end_on_exit=False):
        record = logging.getLogRecordFactory()("x", logging.INFO, __file__, 1, "hi", None, None)
    assert getattr(record, TRACE_ID_FIELD) == ABSENT
    assert getattr(record, TRACE_ID_FIELD) != ZERO_TRACE_HEX


def test_install_is_idempotent() -> None:
    install_trace_logging()
    first = logging.getLogRecordFactory()
    install_trace_logging()
    assert logging.getLogRecordFactory() is first


def test_install_preserves_a_pre_existing_factory() -> None:
    original = logging.getLogRecordFactory()

    def custom(*args: object, **kwargs: object) -> logging.LogRecord:
        record = original(*args, **kwargs)
        record.custom_marker = "kept"  # type: ignore[attr-defined]
        return record

    logging.setLogRecordFactory(custom)
    try:
        install_trace_logging()
        record = logging.getLogRecordFactory()("x", logging.INFO, __file__, 1, "hi", None, None)
        assert getattr(record, "custom_marker") == "kept"
        assert getattr(record, TRACE_ID_FIELD) == ABSENT
    finally:
        logging.setLogRecordFactory(original)


def test_log_format_names_both_fields() -> None:
    fmt = trace_logging_log_format("service=demo")
    assert f"%({TRACE_ID_FIELD})s" in fmt
    assert f"%({SPAN_ID_FIELD})s" in fmt
    assert "service=demo" in fmt


def test_formatted_line_carries_the_ids_end_to_end(caplog) -> None:
    # The actual claim an operator relies on: a rendered line shows the id, and it
    # is the trace's real id rather than a zero placeholder.
    _install_sdk()
    install_trace_logging()
    formatter = logging.Formatter(trace_logging_log_format("service=demo"))
    with use_span(_sampled_span(), end_on_exit=False):
        record = logging.getLogRecordFactory()("demo", logging.INFO, __file__, 1, "priced refund", None, None)
    line = formatter.format(record)
    assert f"trace={TRACE_HEX}" in line
    assert f"span={SPAN_HEX}" in line


def test_event_handler_log_records_carry_the_producers_trace(monkeypatch, caplog) -> None:
    # The end-to-end claim: a line logged in the consuming service joins the trace
    # that started in the producing one. This is the case that used to mean
    # matching wall-clock timestamps across five `kubectl logs` invocations.
    monkeypatch.setenv("OTEL_TRACES_EXPORTER", "otlp")
    monkeypatch.setenv("OTEL_SERVICE_NAME", "python-kit-trace-logging")
    init_opentelemetry("python-kit-trace-logging", span_exporter=InMemorySpanExporter())

    tracer = trace.get_tracer("python-kit-trace-logging")
    with tracer.start_as_current_span("POST /orders") as producer:
        producer_trace = format_trace_id(producer.get_span_context().trace_id)
        envelope = envelope_factory(event_type="OrderPlaced", producer="journey-order", payload={})
    assert envelope.traceparent

    logger = logging.getLogger("python_kit.consumer")

    def handler(_envelope: EventEnvelope) -> HandlerResult:
        # A handler logging exactly the way service code does: no tracing
        # awareness, nothing passed in.
        logger.warning("consumer handled the event")
        return HandlerResult.success()

    caplog.set_level(logging.WARNING)
    InMemoryEventSubscriber([envelope]).subscribe(["events:journey-order"], "notification", "c1", handler)

    records = [r for r in caplog.records if r.getMessage() == "consumer handled the event"]
    assert records, "the handler's log record was not captured"
    assert getattr(records[0], TRACE_ID_FIELD) == producer_trace
    assert getattr(records[0], SPAN_ID_FIELD) != ABSENT


def test_kit_log_records_are_unchanged_when_tracing_is_off(monkeypatch, caplog) -> None:
    # python-kit's own warnings go through the same factory. With no span there
    # must be no id, or an operator reading a line outside a request would see
    # something that looks like a trace and is not one.
    monkeypatch.delenv("OTEL_TRACES_EXPORTER", raising=False)
    install_trace_logging()
    caplog.set_level(logging.WARNING)
    logging.getLogger("python_kit.offline").warning("redis reconnecting")
    record = caplog.records[-1]
    assert getattr(record, TRACE_ID_FIELD) == ABSENT
    assert getattr(record, TRACE_ID_FIELD) != ZERO_TRACE_HEX
