"""Outbound trace-context propagation, in the style of post-sales'
OutboundTracePropagationTest: a real local HTTP server records the headers it
receives, and the assertion is that the request carried the active span's
W3C traceparent.

Covers both transports the kit offers, because the two Python callers use
different ones: urllib (fare-pricing, disruption-recovery) and httpx
(transfer-management).
"""

from __future__ import annotations

from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
import threading
from urllib import request as urllib_request

import pytest

from train_ticket_platform.outbound import (
    inject_trace_context,
    trace_context_headers,
    traced_httpx_client,
    traced_urllib_request,
)

_captured: list[dict[str, str]] = []


class _RecordingHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._record_and_reply()

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        self._record_and_reply()

    def _record_and_reply(self) -> None:
        _captured.append({key.lower(): value for key, value in self.headers.items()})
        body = json.dumps({"ok": True}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args: object) -> None:
        return


@pytest.fixture
def stub_server():
    _captured.clear()
    server = HTTPServer(("127.0.0.1", 0), _RecordingHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


@pytest.fixture
def tracing_enabled():
    """Install an SDK provider and enable tracing for the duration of a test.

    The provider is process-global and cannot be uninstalled, which is fine:
    the tests that assert the disabled path key off OTEL_TRACES_EXPORTER, not
    off the provider.
    """
    from opentelemetry import trace
    from opentelemetry.sdk.trace import TracerProvider

    previous = os.environ.get("OTEL_TRACES_EXPORTER")
    os.environ["OTEL_TRACES_EXPORTER"] = "otlp"
    provider = trace.get_tracer_provider()
    if not hasattr(provider, "add_span_processor"):
        trace.set_tracer_provider(TracerProvider())
    try:
        yield trace.get_tracer("python-kit-test")
    finally:
        if previous is None:
            os.environ.pop("OTEL_TRACES_EXPORTER", None)
        else:
            os.environ["OTEL_TRACES_EXPORTER"] = previous


def _traceparent_trace_id(traceparent: str) -> str:
    return traceparent.split("-")[1]


def test_urllib_request_carries_active_span_traceparent(stub_server, tracing_enabled):
    with tracing_enabled.start_as_current_span("outbound") as span:
        expected_trace_id = format(span.get_span_context().trace_id, "032x")
        req = traced_urllib_request(
            stub_server + "/api/v1/identity-verification/eligibility-certificates",
            method="GET",
            headers={"Accept": "application/json", "X-Correlation-Id": "corr-1"},
        )
        with urllib_request.urlopen(req, timeout=5) as response:
            assert response.status == 200

    headers = _captured[-1]
    assert "traceparent" in headers, "outbound urllib request must carry a traceparent"
    assert _traceparent_trace_id(headers["traceparent"]) == expected_trace_id
    # The headers the call sites already set must survive untouched.
    assert headers["x-correlation-id"] == "corr-1"
    assert headers["accept"] == "application/json"


def test_httpx_client_carries_active_span_traceparent(stub_server, tracing_enabled):
    with tracing_enabled.start_as_current_span("outbound") as span:
        expected_trace_id = format(span.get_span_context().trace_id, "032x")
        with traced_httpx_client(timeout=5.0) as client:
            response = client.post(
                stub_server + "/api/v1/disruptions",
                json={"a": 1},
                headers={"Idempotency-Key": "idem-1", "X-Correlation-Id": "corr-2"},
            )
        assert response.status_code == 200

    headers = _captured[-1]
    assert "traceparent" in headers, "outbound httpx request must carry a traceparent"
    assert _traceparent_trace_id(headers["traceparent"]) == expected_trace_id
    assert headers["idempotency-key"] == "idem-1"
    assert headers["x-correlation-id"] == "corr-2"


def test_httpx_client_injects_a_fresh_traceparent_per_request(stub_server, tracing_enabled):
    """One client, two spans: the header must follow the active span, not the
    span that happened to be current when the client was constructed."""
    with traced_httpx_client(timeout=5.0) as client:
        with tracing_enabled.start_as_current_span("first") as first:
            first_id = format(first.get_span_context().trace_id, "032x")
            client.get(stub_server + "/first")
        with tracing_enabled.start_as_current_span("second") as second:
            second_id = format(second.get_span_context().trace_id, "032x")
            client.get(stub_server + "/second")

    assert first_id != second_id
    assert _traceparent_trace_id(_captured[-2]["traceparent"]) == first_id
    assert _traceparent_trace_id(_captured[-1]["traceparent"]) == second_id


def test_no_traceparent_without_an_active_span(stub_server, tracing_enabled):
    req = traced_urllib_request(stub_server + "/no-span", method="GET")
    with urllib_request.urlopen(req, timeout=5) as response:
        assert response.status == 200
    assert "traceparent" not in _captured[-1]


def test_no_traceparent_when_tracing_is_disabled(stub_server):
    previous = os.environ.pop("OTEL_TRACES_EXPORTER", None)
    try:
        assert trace_context_headers() == {}
        req = traced_urllib_request(stub_server + "/disabled", method="GET")
        with urllib_request.urlopen(req, timeout=5) as response:
            assert response.status == 200
    finally:
        if previous is not None:
            os.environ["OTEL_TRACES_EXPORTER"] = previous
    assert "traceparent" not in _captured[-1]


def test_inject_replaces_a_stale_inbound_traceparent(tracing_enabled):
    stale = "00-11111111111111111111111111111111-2222222222222222-01"
    with tracing_enabled.start_as_current_span("outbound") as span:
        expected_trace_id = format(span.get_span_context().trace_id, "032x")
        headers = inject_trace_context({"traceparent": stale, "Idempotency-Key": "idem-3"})
    assert _traceparent_trace_id(headers["traceparent"]) == expected_trace_id
    assert headers["Idempotency-Key"] == "idem-3"


def test_inject_is_case_insensitive_about_stale_headers(tracing_enabled):
    stale = "00-11111111111111111111111111111111-2222222222222222-01"
    with tracing_enabled.start_as_current_span("outbound"):
        headers = inject_trace_context({"TraceParent": stale})
    # Exactly one traceparent must survive, or a strict callee may read the
    # stale one.
    assert [key for key in headers if key.lower() == "traceparent"] == ["traceparent"]
