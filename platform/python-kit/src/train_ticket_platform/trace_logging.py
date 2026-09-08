"""Publish the current span's ids onto every log record.

WHY THIS EXISTS
---------------
The platform had full trace propagation (W3C ``traceparent`` across HTTP and
through event envelopes) and structured logs, and no way to get from one to the
other: nothing put a trace id where a log record could see it, and no log format
printed one. So a line reporting a refund priced at zero could not be tied to the
trace that produced it, and a trace showing a failed span could not be tied to
the line explaining why. Diagnosing the zero-refund chain made it concrete: five
services, each taking a silent early return, correlated only by matching
wall-clock timestamps by hand across five ``kubectl logs`` invocations.

This is the Python half of the convention java-kit's ``TraceLoggingContext``
established. ``trace_id``/``span_id`` are the field names the OpenTelemetry log
appenders use, so a service that later adopts one gets the same names rather than
a second convention.

WHY A LogRecordFactory AND NOT A Filter
---------------------------------------
A ``logging.Filter`` only runs for records passing through the handlers it is
attached to, so a service that adds a handler later, or logs through a logger
whose records reach a different handler, silently loses the fields. The record
factory runs for *every* record the ``logging`` module creates, before any
handler or filter sees it -- the same "inject once, centrally" property java-kit
gets from the log pattern. It is also what makes ``%(trace_id)s`` safe in a
format string: every record has the attribute, so no handler can raise
``KeyError`` on a line logged outside a span.

WHERE THE IDS COME FROM
-----------------------
``trace.get_current_span()``, not a parallel contextvar. The SDK already carries
the span in a contextvar, and python-kit's consumer path uses
``start_as_current_span``, so the span is genuinely current inside a handler.
Maintaining a second store would be a copy that can drift out of sync with the
one the traces themselves are built from.
"""

from __future__ import annotations

import logging
from typing import Any, Callable

__all__ = [
    "SPAN_ID_FIELD",
    "TRACE_ID_FIELD",
    "current_trace_logging_ids",
    "install_trace_logging",
    "trace_logging_log_format",
]

# The names the OpenTelemetry logging appenders use in every language.
TRACE_ID_FIELD = "trace_id"
SPAN_ID_FIELD = "span_id"

# An absent or non-recording span yields an all-zero trace id. Emitting that is
# worse than emitting nothing: it looks like a real id and joins every unrelated
# line in the log together. Records logged outside a span get this instead, which
# is visibly not an id.
ABSENT = "-"

_ZERO_TRACE_ID = "0" * 32
_ZERO_SPAN_ID = "0" * 16

_installed_factory: Callable[..., logging.LogRecord] | None = None


def current_trace_logging_ids() -> dict[str, str]:
    """The ids a record created right now should carry, empty when there is no span.

    Empty rather than zero-filled, and empty for any failure reading the span:
    observability wiring must never be the reason a service cannot log.
    """
    try:
        from opentelemetry import trace
        from opentelemetry.trace import format_span_id, format_trace_id

        span_context = trace.get_current_span().get_span_context()
    except Exception:  # pragma: no cover - opentelemetry is a hard dependency
        return {}
    if not getattr(span_context, "is_valid", False):
        return {}
    trace_id = format_trace_id(span_context.trace_id)
    span_id = format_span_id(span_context.span_id)
    if trace_id == _ZERO_TRACE_ID or span_id == _ZERO_SPAN_ID:
        # is_valid should already exclude these; checked anyway because a bogus id
        # that looks real is the one outcome worse than no id at all.
        return {}
    return {TRACE_ID_FIELD: trace_id, SPAN_ID_FIELD: span_id}


def install_trace_logging() -> None:
    """Give every log record ``trace_id`` and ``span_id`` attributes.

    Idempotent: installing twice does not chain two factories, so a service that
    calls it from both ``main`` and ``create_app`` pays for it once.

    Wraps whatever factory is already installed rather than replacing it, so a
    service that sets its own factory keeps it.
    """
    global _installed_factory
    if _installed_factory is not None and logging.getLogRecordFactory() is _installed_factory:
        return
    previous = logging.getLogRecordFactory()

    def factory(*args: Any, **kwargs: Any) -> logging.LogRecord:
        record = previous(*args, **kwargs)
        ids = current_trace_logging_ids()
        # Attributes are set unconditionally, with ABSENT when there is no span,
        # so a format string naming them can never raise on a startup or shutdown
        # line logged outside a request.
        setattr(record, TRACE_ID_FIELD, ids.get(TRACE_ID_FIELD, ABSENT))
        setattr(record, SPAN_ID_FIELD, ids.get(SPAN_ID_FIELD, ABSENT))
        return record

    logging.setLogRecordFactory(factory)
    _installed_factory = factory


def trace_logging_log_format(prefix: str = "") -> str:
    """A ``logging`` format string that prints the ids.

    ``install_trace_logging`` puts the ids on the record, but a format string that
    does not name them changes nothing an operator can see -- the same reason
    java-kit ships a log pattern alongside the MDC bind. Services pass their
    existing ``service=<name>`` prefix through so the only change to their line is
    the two new fields.
    """
    lead = f"{prefix} " if prefix else ""
    return (
        "%(asctime)s %(levelname)s "
        + lead
        + f"trace=%({TRACE_ID_FIELD})s span=%({SPAN_ID_FIELD})s "
        + "%(name)s %(message)s"
    )
