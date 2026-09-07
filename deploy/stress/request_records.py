#!/usr/bin/env python3
"""Per-request outcome records for the stress driver (issue #420).

The driver's aggregates -- ``LatencyTracker`` percentiles, ``status_counts``,
``error_counts``, the chain summaries in the report JSON -- can only answer
questions that were asked before the run started. They cannot be re-sliced by
sub-window, they pair no status with the latency that produced it, and they
carry no trace id, so a client-observed timeout cannot be tied to the server
span that caused it.

This module writes one JSON Lines row per client HTTP request. It is kept in
its own module, free of the driver's third-party dependencies (aiohttp, redis,
yaml), so it is unit-testable with the standard library alone:

    python3 -m unittest discover -s deploy/stress -p 'test_*.py'

DESIGN CONSTRAINT: recording must not change the offered load.
=============================================================
The stress driver is open-loop: a token bucket paces arrivals on a single
asyncio event loop. Anything that blocks that loop -- a ``write()`` syscall, an
``fsync``, a lock held by a slow disk -- delays the *dispatcher*, and the
offered rate silently drops below the configured RPS. That would corrupt every
measurement the driver exists to make.

So the request coroutine does exactly one thing per record: a non-blocking
``put_nowait`` of a plain tuple onto a bounded queue. No formatting, no JSON
encoding, no file object, no ``await``, no syscall. A dedicated *writer thread*
owns the file and does all the expensive work; while it is inside ``write()``
CPython has released the GIL, so the event loop runs concurrently with the
actual I/O.

The put is non-blocking on purpose. When the queue is full, records are
DROPPED and counted rather than allowed to back-pressure into the dispatcher:
a visible, quantified hole in the record file is strictly better than an
invisible dent in the offered load.
"""

from __future__ import annotations

import json
import os
import queue
import random
import threading
import time
from typing import Any

__all__ = [
    "RequestRecorder",
    "TraceContext",
    "new_trace_context",
    "parse_traceparent",
    "route_template",
    "RECORD_FIELDS",
]


# ---------------------------------------------------------------------------
# W3C trace context
# ---------------------------------------------------------------------------


class TraceContext:
    """One originated W3C trace context.

    The driver is the ORIGIN of its requests: there is no inbound traceparent
    to continue, so it mints one per request. The same id goes on the wire and
    into the record file, which is what makes a recorded row joinable to the
    server-side spans in Jaeger.
    """

    __slots__ = ("trace_id", "span_id", "sampled", "header")

    def __init__(self, trace_id: str, span_id: str, sampled: bool, header: str):
        self.trace_id = trace_id
        self.span_id = span_id
        self.sampled = sampled
        self.header = header


def new_trace_context(sampled: bool = True) -> TraceContext:
    """Mint a spec-compliant traceparent: ``00-<32 hex>-<16 hex>-<flags>``.

    Randomness comes from the ``random`` module, not ``secrets``: trace ids
    must be unique, not unpredictable, and this runs on the request path.
    All-zero ids are invalid per the spec and are rejected by conformant
    receivers, so they are forced non-zero.
    """
    trace = random.getrandbits(128) or 1
    span = random.getrandbits(64) or 1
    trace_id = "%032x" % trace
    span_id = "%016x" % span
    flags = "01" if sampled else "00"
    return TraceContext(trace_id, span_id, sampled, f"00-{trace_id}-{span_id}-{flags}")


def parse_traceparent(header: str) -> TraceContext:
    """Read back a traceparent already present on a request.

    Covers the issue's "or reads back the one an instrumented client
    generated" alternative: whatever is on the wire is what gets recorded,
    never a second id invented here. A malformed header yields empty ids.
    """
    parts = (header or "").split("-")
    if len(parts) != 4 or len(parts[1]) != 32 or len(parts[2]) != 16:
        return TraceContext("", "", False, header or "")
    try:
        int(parts[1], 16)
        int(parts[2], 16)
        sampled = bool(int(parts[3], 16) & 0x01)
    except ValueError:
        return TraceContext("", "", False, header)
    return TraceContext(parts[1].lower(), parts[2].lower(), sampled, header)


# ---------------------------------------------------------------------------
# route templating
# ---------------------------------------------------------------------------


def route_template(path: str) -> str:
    """Collapse a concrete path into a route template.

    Without this, grouping by endpoint is impossible: every
    ``/api/v1/orders/<uuid>`` is its own key. Runs on the WRITER THREAD, never
    on the request path.

    Conservative by design: only segments that contain a digit are collapsed,
    so ``orders`` and ``payment-channel`` survive, and short all-digit-free
    segments like ``v1`` are left alone.
    """
    if not path:
        return ""
    path = path.split("?", 1)[0]
    out = []
    for seg in path.split("/"):
        out.append("{id}" if _looks_like_id(seg) else seg)
    return "/".join(out)


def _looks_like_id(seg: str) -> bool:
    if len(seg) < 2:
        return False
    has_digit = False
    for ch in seg:
        if ch.isdigit():
            has_digit = True
        elif not (ch.isalpha() and ch.isascii()) and ch not in "-_":
            return False
    if not has_digit:
        return False
    if len(seg) >= 8:
        return True
    return seg.isdigit()


# ---------------------------------------------------------------------------
# the recorder
# ---------------------------------------------------------------------------

# On-disk JSON Lines field order. Every field is always present (no omitted
# keys) so the file is a stable rectangle that loads straight into
# pandas/DuckDB/jq with no per-row key checks, and an empty ``error`` is
# meaningful ("this request did get a status").
RECORD_FIELDS = (
    "ts",  # request start, RFC3339 with microseconds, UTC
    "chain",  # chain/journey that issued the request
    "step",  # step label within the chain
    "service",  # target service
    "method",  # HTTP method
    "route",  # route template, ids replaced by {id}
    "path",  # raw path as requested
    "status",  # 0 == never got a status
    "latency_ms",  # milliseconds
    "error",  # transport-error marker; "" when a status was received
    "trace_id",  # joins to server spans in Jaeger
    "span_id",  # client-side span id
    "sampled",  # traceparent sampled flag as sent
)

_SENTINEL = object()


class RequestRecorder:
    """Writes one JSON Lines record per client HTTP request.

    Every method is safe to call on ``None`` via the module-level helpers
    below, so callers do not need a nil check on the request path.
    """

    def __init__(
        self,
        path: str,
        buffer_records: int = 65536,
        flush_interval_seconds: float = 2.0,
        max_file_megabytes: int = 512,
        trace_sampled_ratio: float = 1.0,
    ) -> None:
        if not path or not path.strip():
            raise ValueError("RequestRecorder needs a path")
        self.path = path
        self._max_bytes = int(max_file_megabytes) * 1024 * 1024
        self._flush_interval = float(flush_interval_seconds) or 2.0
        self.trace_sampled_ratio = min(1.0, max(0.0, float(trace_sampled_ratio)))

        directory = os.path.dirname(path)
        if directory:
            os.makedirs(directory, exist_ok=True)

        # Open here, on the CALLER's thread, not inside the writer thread.
        # Two reasons: a bad path or a permission problem raises from the
        # constructor where it can be reported, instead of killing a
        # background thread and silently recording nothing; and the file
        # exists the moment recording is on, so `tail -f` works from the
        # start of the run.
        #
        # 256 KiB of userspace buffering turns thousands of tiny writes into a
        # handful of syscalls. Mode "a" (O_APPEND), never truncate: the record
        # file outlives the run, and a re-run must not erase the previous one.
        self._file = open(path, "a", buffering=256 * 1024, encoding="utf-8")

        self._q: queue.Queue = queue.Queue(maxsize=max(1, int(buffer_records)))
        # Two counters, each owned by exactly one thread: the producer only
        # ever touches _dropped, the writer only ever touches _written. No
        # cross-thread mutation, so no lock is needed on the request path.
        self._dropped = 0
        self._written = 0
        self._closed = False
        self._thread = threading.Thread(
            target=self._write_loop, name="request-recorder", daemon=True
        )
        self._thread.start()

    # -- request path -------------------------------------------------------

    def record(
        self,
        start_unix_ns: int,
        chain: str,
        step: str,
        service: str,
        method: str,
        path: str,
        status: int,
        latency_ms: float,
        transport_error: str,
        trace_id: str,
        span_id: str,
        sampled: bool,
    ) -> None:
        """Hand one record to the writer thread.

        The ONLY recorder call on a request coroutine, and deliberately
        trivial: one tuple allocation and a non-blocking queue put. It cannot
        block and performs no I/O, so the offered rate is independent of how
        fast (or whether) the disk accepts writes.
        """
        try:
            self._q.put_nowait(
                (
                    start_unix_ns,
                    chain,
                    step,
                    service,
                    method,
                    path,
                    status,
                    latency_ms,
                    transport_error,
                    trace_id,
                    span_id,
                    sampled,
                )
            )
        except queue.Full:
            self._dropped += 1

    def trace_sampled(self) -> bool:
        """The sampled flag for the next traceparent."""
        ratio = self.trace_sampled_ratio
        if ratio >= 1.0:
            return True
        if ratio <= 0.0:
            return False
        return random.random() < ratio

    # -- reporting / lifecycle ---------------------------------------------

    def counters(self) -> tuple[int, int]:
        """(written, dropped)."""
        return self._written, self._dropped

    def close(self) -> None:
        """Drain the queue, flush and close the file. Idempotent."""
        if self._closed:
            return
        self._closed = True
        self._q.put(_SENTINEL)
        self._thread.join(timeout=30)

    # -- writer thread ------------------------------------------------------

    def _write_loop(self) -> None:
        f = self._file
        size = f.tell()
        dumps = json.dumps
        fields = RECORD_FIELDS
        next_flush = time.monotonic() + self._flush_interval

        try:
            while True:
                timeout = max(0.01, next_flush - time.monotonic())
                try:
                    item = self._q.get(timeout=timeout)
                except queue.Empty:
                    # Periodic flush is what makes the file readable *during* a
                    # run and survivable across a SIGKILL, not just at a clean
                    # shutdown.
                    f.flush()
                    next_flush = time.monotonic() + self._flush_interval
                    continue

                if item is _SENTINEL:
                    return

                (
                    start_ns,
                    chain,
                    step,
                    service,
                    method,
                    path,
                    status,
                    latency_ms,
                    transport_error,
                    trace_id,
                    span_id,
                    sampled,
                ) = item
                row = dict(
                    zip(
                        fields,
                        (
                            _iso_nanos(start_ns),
                            chain,
                            step,
                            service,
                            method,
                            route_template(path),
                            path,
                            status,
                            round(latency_ms, 3),
                            transport_error,
                            trace_id,
                            span_id,
                            bool(sampled),
                        ),
                    )
                )
                line = dumps(row, separators=(",", ":")) + "\n"
                f.write(line)
                self._written += 1
                size += len(line)

                # Bounded disk: a long scenario against a small container
                # filesystem must not become a disk-pressure incident. Keep at
                # most the current file plus one predecessor.
                if self._max_bytes > 0 and size >= self._max_bytes:
                    f.flush()
                    f.close()
                    try:
                        os.replace(self.path, self.path + ".1")
                    except OSError:
                        pass
                    f = open(self.path, "a", buffering=256 * 1024, encoding="utf-8")
                    size = 0

                if time.monotonic() >= next_flush:
                    f.flush()
                    next_flush = time.monotonic() + self._flush_interval
        finally:
            try:
                f.flush()
            finally:
                f.close()


def _iso_nanos(start_unix_ns: int) -> str:
    """RFC3339 UTC with microsecond precision.

    Formatted on the writer thread, not the request path. Microseconds rather
    than nanoseconds because ``time.time_ns()`` on Linux/CPython does not
    resolve finer than that in practice, and a fake precision is worse than an
    honest one.
    """
    secs, rem_ns = divmod(int(start_unix_ns), 1_000_000_000)
    micros = rem_ns // 1000
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(secs)) + ".%06dZ" % micros


# ---------------------------------------------------------------------------
# None-safe helpers -- "recording disabled" is represented by a None recorder,
# so the request path never needs a conditional.
# ---------------------------------------------------------------------------


def recorder_trace_sampled(rec: "RequestRecorder | None") -> bool:
    """Sampled flag, defaulting to True when recording is off.

    traceparent propagation is deliberately independent of whether records are
    written: the services should still get a trace parent either way.
    """
    return True if rec is None else rec.trace_sampled()


def classify_transport_error(exc: BaseException) -> str:
    """Map a client-side failure to a short, low-cardinality marker.

    This is the "never got a status" marker: a row with ``status == 0`` always
    carries a non-empty value here. Only reached on the failure path.
    """
    name = type(exc).__name__
    text = str(exc).lower()
    if "timeout" in name.lower() or "timeout" in text:
        return "timeout"
    if isinstance(exc, (ConnectionResetError,)) or "connection reset" in text:
        return "connection_reset"
    if isinstance(exc, ConnectionRefusedError) or "cannot connect" in text or "refused" in text:
        return "connect_failed"
    if "cancelled" in name.lower() or "canceled" in name.lower():
        return "canceled"
    if isinstance(exc, OSError):
        return "os_error"
    return "transport"


def recording_config(cfg: dict[str, Any], default_path: str | None) -> dict[str, Any]:
    """Resolve the scenario YAML's ``recording:`` section.

    Every value is configuration, never a constant. ``default_path`` is the
    path derived from ``--records`` / the report path; the scenario can
    override it, and ``enabled: false`` turns recording off entirely.
    """
    raw = cfg.get("recording") or {}
    if not isinstance(raw, dict):
        raw = {}
    path = raw.get("path") or default_path
    enabled = bool(raw.get("enabled", True)) and bool(path)
    return {
        "enabled": enabled,
        "path": path,
        "buffer_records": int(raw.get("buffer_records", 65536)),
        "flush_interval_seconds": float(raw.get("flush_interval_seconds", 2.0)),
        "max_file_megabytes": int(raw.get("max_file_megabytes", 512)),
        # 1.0 by default and deliberately so: the driver ORIGINATES these
        # traces, so it owns the sampling decision. With flags=00 a conformant
        # service records no span at all and the recorded trace id would point
        # at nothing in Jaeger -- the one thing this feature exists to
        # prevent. Lower it only to shed tracing-backend volume; rows then
        # carry sampled=false so an analysis can tell "no span was ever
        # recorded" from "the span is missing".
        "trace_sampled_ratio": float(raw.get("trace_sampled_ratio", 1.0)),
    }


def open_recorder(cfg: dict[str, Any], default_path: str | None) -> "RequestRecorder | None":
    """Build a recorder from a scenario config, or None when disabled."""
    rc = recording_config(cfg, default_path)
    if not rc["enabled"]:
        return None
    return RequestRecorder(
        path=rc["path"],
        buffer_records=rc["buffer_records"],
        flush_interval_seconds=rc["flush_interval_seconds"],
        max_file_megabytes=rc["max_file_megabytes"],
        trace_sampled_ratio=rc["trace_sampled_ratio"],
    )
