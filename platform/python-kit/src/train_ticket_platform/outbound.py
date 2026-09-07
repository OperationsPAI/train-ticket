"""Outbound HTTP trace-context propagation for Python services.

The synchronous half of a trace only holds together if every outbound call
carries the W3C ``traceparent`` of the active span. Rather than hand-writing
that header at each call site, services take a client from this module: the
propagation is installed once, here, and call sites get it by construction.

Two transports are covered because both are in use:

* ``urllib.request`` -- build requests with :func:`traced_urllib_request`
  instead of ``urllib.request.Request``.
* ``httpx`` -- build clients with :func:`traced_httpx_client` instead of
  ``httpx.Client``; a request event hook injects per request, because
  ``traceparent`` changes with every span and cannot be a static default
  header.

Everything degrades to a no-op when tracing is disabled or no span is active,
so unit tests and local runs see byte-identical requests to before.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, MutableMapping
from typing import Any

from .observability import otel_tracing_enabled

__all__ = [
    "TRACE_CONTEXT_HEADERS",
    "httpx_module",
    "inject_trace_context",
    "trace_context_headers",
    "traced_httpx_client",
    "traced_urllib_request",
]

# W3C trace context defines exactly these two headers; the propagator only ever
# sets them, and they are the ones a callee's extractor reads.
TRACE_CONTEXT_HEADERS: tuple[str, ...] = ("traceparent", "tracestate")


def trace_context_headers() -> dict[str, str]:
    """Return the active span's W3C trace-context headers.

    Empty when tracing is off, when no SDK provider is installed, or when no
    span is active -- an invalid span context must not produce a bogus
    ``traceparent`` that would strand the callee in a nonexistent trace.
    """
    if not otel_tracing_enabled():
        return {}
    try:
        from opentelemetry import trace
        from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator
    except ImportError:  # pragma: no cover - opentelemetry is a hard dependency
        return {}
    span_context = trace.get_current_span().get_span_context()
    if not span_context.is_valid:
        return {}
    carrier: dict[str, str] = {}
    # W3C propagator explicitly rather than the global one: java-kit pins W3C
    # too, and the callee extractors across all five kits read exactly these
    # two headers. A globally configured b3-only propagator would otherwise
    # break the chain silently.
    TraceContextTextMapPropagator().inject(carrier)
    return {key: value for key, value in carrier.items() if key.lower() in TRACE_CONTEXT_HEADERS and value}


def inject_trace_context(headers: MutableMapping[str, str] | None = None) -> MutableMapping[str, str]:
    """Add the active trace context to ``headers`` and return it.

    Existing ``traceparent``/``tracestate`` entries are replaced: a stale value
    copied from an inbound request would parent the callee to the wrong span.
    Any other header the caller set -- ``X-Correlation-Id``,
    ``Idempotency-Key`` -- is left untouched.
    """
    target: MutableMapping[str, str] = {} if headers is None else headers
    injected = trace_context_headers()
    if not injected:
        return target
    _remove_existing_trace_headers(target)
    target.update(injected)
    return target


def traced_urllib_request(
    url: str,
    *,
    data: bytes | None = None,
    method: str = "GET",
    headers: Mapping[str, str] | None = None,
) -> Any:
    """Build a ``urllib.request.Request`` carrying the active trace context."""
    from urllib import request as urllib_request

    merged: dict[str, str] = dict(headers or {})
    inject_trace_context(merged)
    return urllib_request.Request(url, data=data, method=method, headers=merged)


def traced_httpx_client(**kwargs: Any) -> Any:
    """Build an ``httpx.Client`` that injects trace context on every request.

    A request event hook, not a default header: ``traceparent`` names the
    *current* span, so a value baked in at construction time would be wrong for
    every request but the first.

    ``event_hooks`` supplied by the caller are preserved; the trace hook runs
    first so a caller hook can still observe or override the header.
    ``httpx_module`` lets a caller pin the transport explicitly; by default the
    real ``httpx`` is used when installed, falling back to the API-compatible
    ``httpx2`` that python-kit itself vendors.
    """
    httpx = kwargs.pop("httpx_module", None) or httpx_module()

    event_hooks = dict(kwargs.pop("event_hooks", None) or {})
    request_hooks = list(event_hooks.get("request") or [])
    event_hooks["request"] = [_inject_into_httpx_request, *request_hooks]
    return httpx.Client(event_hooks=event_hooks, **kwargs)


def httpx_module() -> Any:
    """Return the installed httpx implementation.

    Services depend on ``httpx``; python-kit's own environment vendors
    ``httpx2``. Both expose the ``Client(event_hooks=...)`` API this module
    needs, so either is usable and the kit does not force a service to add a
    dependency it does not have.
    """
    try:
        import httpx

        return httpx
    except ImportError:
        import httpx2

        return httpx2


def _inject_into_httpx_request(request: Any) -> None:
    injected = trace_context_headers()
    if not injected:
        return
    for key, value in injected.items():
        # httpx headers are multi-valued; assignment replaces every existing
        # entry, which is what a fresh trace context requires.
        request.headers[key] = value


def _remove_existing_trace_headers(headers: MutableMapping[str, str]) -> None:
    stale = [key for key in _header_keys(headers) if key.lower() in TRACE_CONTEXT_HEADERS]
    for key in stale:
        try:
            del headers[key]
        except KeyError:  # pragma: no cover - concurrent mutation only
            pass


def _header_keys(headers: MutableMapping[str, str]) -> Iterable[str]:
    return list(headers.keys())
