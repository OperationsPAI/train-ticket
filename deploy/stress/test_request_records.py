#!/usr/bin/env python3
"""Tests for the stress driver's per-request record output (issue #420).

Standard library only, on purpose: driver.py needs aiohttp/redis/yaml, but
request_records.py deliberately does not, so the record schema, the
traceparent generation and -- most importantly -- the "recording does not
change the offered load" design can all be tested anywhere.

    cd deploy/stress && python3 -m unittest discover -p 'test_*.py' -v
"""

from __future__ import annotations

import json
import os
import tempfile
import threading
import time
import unittest

from request_records import (
    RECORD_FIELDS,
    RequestRecorder,
    classify_transport_error,
    new_trace_context,
    open_recorder,
    parse_traceparent,
    recorder_trace_sampled,
    recording_config,
    route_template,
)


def read_records(path: str) -> list[dict]:
    with open(path) as f:
        return [json.loads(line) for line in f if line.strip()]


class RecordSchemaTest(unittest.TestCase):
    """Pins the schema against the field list issue #420 requires."""

    def setUp(self) -> None:
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.path = os.path.join(self.dir.name, "requests.jsonl")

    def test_every_required_field_is_present_and_typed(self) -> None:
        rec = RequestRecorder(self.path, flush_interval_seconds=3600)
        tc = new_trace_context(True)
        rec.record(
            time.time_ns(),
            "purchase",
            "confirm-order",
            "order",
            "POST",
            "/api/v1/orders/0190f0ab-1111-7000-8000-000000000001/confirm?force=true",
            201,
            12.3456,
            "",
            tc.trace_id,
            tc.span_id,
            tc.sampled,
        )
        rec.close()

        rows = read_records(self.path)
        self.assertEqual(len(rows), 1)
        row = rows[0]

        # Exact field set, in the documented order: downstream analyses treat
        # this file as a stable rectangle.
        self.assertEqual(tuple(row.keys()), RECORD_FIELDS)

        # request start timestamp
        self.assertRegex(row["ts"], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$")
        # chain / journey name
        self.assertEqual(row["chain"], "purchase")
        self.assertEqual(row["step"], "confirm-order")
        # target service, method
        self.assertEqual(row["service"], "order")
        self.assertEqual(row["method"], "POST")
        # route template: id collapsed, query string dropped
        self.assertEqual(row["route"], "/api/v1/orders/{id}/confirm")
        self.assertTrue(row["path"].startswith("/api/v1/orders/0190f0ab-"))
        # status + latency
        self.assertEqual(row["status"], 201)
        self.assertAlmostEqual(row["latency_ms"], 12.346, places=3)
        # transport-error marker is empty because a status WAS received
        self.assertEqual(row["error"], "")
        # trace id the request carried
        self.assertEqual(len(row["trace_id"]), 32)
        self.assertEqual(len(row["span_id"]), 16)
        self.assertTrue(row["sampled"])

    def test_transport_failure_is_status_zero_with_a_marker(self) -> None:
        rec = RequestRecorder(self.path, flush_interval_seconds=3600)
        tc = new_trace_context(True)
        rec.record(
            time.time_ns(), "refund", "get-order", "order", "GET",
            "/api/v1/orders/123", 0, 5000.0, "timeout",
            tc.trace_id, tc.span_id, tc.sampled,
        )
        rec.close()
        row = read_records(self.path)[0]
        self.assertEqual(row["status"], 0)
        self.assertEqual(row["error"], "timeout")
        # A request that never got a status is the row you most want to trace.
        self.assertEqual(len(row["trace_id"]), 32)

    def test_file_is_line_oriented_and_one_row_per_request(self) -> None:
        rec = RequestRecorder(self.path, flush_interval_seconds=3600)
        for i in range(250):
            tc = new_trace_context(True)
            rec.record(time.time_ns(), "browse", "s", "order", "GET",
                       f"/api/v1/orders?offset={i}", 200, 1.0, "",
                       tc.trace_id, tc.span_id, True)
        rec.close()
        with open(self.path) as f:
            lines = f.read().splitlines()
        self.assertEqual(len(lines), 250)
        for line in lines:
            json.loads(line)  # each line parses standalone


class TraceparentTest(unittest.TestCase):
    def test_generated_traceparent_is_spec_compliant(self) -> None:
        for _ in range(500):
            tc = new_trace_context(True)
            parts = tc.header.split("-")
            self.assertEqual(len(parts), 4, tc.header)
            self.assertEqual(parts[0], "00")
            self.assertEqual(len(parts[1]), 32)
            self.assertEqual(len(parts[2]), 16)
            self.assertEqual(parts[3], "01")
            self.assertEqual(len(tc.header), 55)
            int(parts[1], 16)
            int(parts[2], 16)
            # An all-zero id is invalid per the spec and is dropped by
            # conformant receivers.
            self.assertNotEqual(parts[1], "0" * 32)
            self.assertNotEqual(parts[2], "0" * 16)
            # The recorded id must be the id on the wire.
            self.assertEqual(tc.trace_id, parts[1])
            self.assertEqual(tc.span_id, parts[2])

    def test_unsampled_clears_the_flag_only(self) -> None:
        tc = new_trace_context(False)
        self.assertTrue(tc.header.endswith("-00"))
        self.assertFalse(tc.sampled)
        # Ids are still valid: an unsampled trace is still identifiable.
        self.assertEqual(len(tc.trace_id), 32)

    def test_trace_ids_are_distinct_per_request(self) -> None:
        ids = {new_trace_context(True).trace_id for _ in range(20000)}
        self.assertEqual(len(ids), 20000)

    def test_parse_reads_back_an_existing_traceparent(self) -> None:
        h = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        tc = parse_traceparent(h)
        self.assertEqual(tc.trace_id, "4bf92f3577b34da6a3ce929d0e0e4736")
        self.assertEqual(tc.span_id, "00f067aa0ba902b7")
        self.assertTrue(tc.sampled)
        self.assertEqual(tc.header, h)

        unsampled = parse_traceparent(
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00"
        )
        self.assertFalse(unsampled.sampled)

    def test_parse_rejects_malformed_headers_without_raising(self) -> None:
        for bad in ("", "garbage", "00-short-00f067aa0ba902b7-01",
                    "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7",
                    "00-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz-00f067aa0ba902b7-01"):
            tc = parse_traceparent(bad)
            self.assertEqual(tc.trace_id, "", bad)
            self.assertFalse(tc.sampled, bad)

    def test_sampled_ratio_knob(self) -> None:
        dirobj = tempfile.TemporaryDirectory()
        self.addCleanup(dirobj.cleanup)
        path = os.path.join(dirobj.name, "r.jsonl")

        always = RequestRecorder(path, trace_sampled_ratio=1.0)
        self.addCleanup(always.close)
        self.assertTrue(all(always.trace_sampled() for _ in range(200)))

        never = RequestRecorder(path + ".2", trace_sampled_ratio=0.0)
        self.addCleanup(never.close)
        self.assertFalse(any(never.trace_sampled() for _ in range(200)))

        half = RequestRecorder(path + ".3", trace_sampled_ratio=0.5)
        self.addCleanup(half.close)
        hits = sum(half.trace_sampled() for _ in range(20000))
        self.assertGreater(hits, 9000)
        self.assertLess(hits, 11000)

    def test_traceparent_is_sent_even_with_recording_disabled(self) -> None:
        # Propagation must not depend on whether records are written, or the
        # services lose their trace parent for no reason.
        self.assertTrue(recorder_trace_sampled(None))


class LoadNeutralityTest(unittest.TestCase):
    """The 'recording does not change the offered load' acceptance criterion."""

    def test_record_never_blocks_and_does_no_io_on_the_request_path(self) -> None:
        # A recorder whose writer thread is parked, so the queue can never
        # drain. If record() blocked on a full queue, or wrote through to the
        # file itself, this test could not finish.
        dirobj = tempfile.TemporaryDirectory()
        self.addCleanup(dirobj.cleanup)
        path = os.path.join(dirobj.name, "requests.jsonl")

        rec = RequestRecorder(path, buffer_records=8, flush_interval_seconds=3600)
        # Deliberately tiny buffer: the writer thread cannot keep up, so the
        # queue stays full and every record() call hits the overflow branch.
        n = 200000
        start = time.monotonic()
        for _ in range(n):
            rec.record(time.time_ns(), "purchase", "s", "order", "GET",
                       "/api/v1/orders", 200, 1.0, "", "a" * 32, "b" * 16, True)
        elapsed = time.monotonic() - start

        # A blocking design would take orders of magnitude longer (or hang).
        self.assertLess(elapsed, 10.0,
                        f"{n} record() calls took {elapsed:.2f}s -- record() is blocking")

        # Account for every record only AFTER close() has drained the queue:
        # before that, in-flight records are legitimately in neither counter.
        rec.close()
        written, dropped = rec.counters()
        self.assertGreater(dropped, 0, "a buffer of 8 must overflow on 200k records")
        self.assertEqual(written + dropped, n,
                         "every record must be either written or counted as dropped")
        print(f"\n  record(): {n} calls in {elapsed*1000:.1f}ms "
              f"({elapsed/n*1e9:.0f} ns/call), {dropped} dropped, {written} written")

    def test_overflow_drops_rather_than_back_pressures(self) -> None:
        dirobj = tempfile.TemporaryDirectory()
        self.addCleanup(dirobj.cleanup)
        path = os.path.join(dirobj.name, "requests.jsonl")
        rec = RequestRecorder(path, buffer_records=4, flush_interval_seconds=3600)
        for _ in range(50000):
            rec.record(0, "c", "s", "svc", "GET", "/p", 200, 1.0, "", "", "", True)
        rec.close()  # drain first: in-flight records are in neither counter
        written, dropped = rec.counters()
        # The trade is explicit and quantified: a hole in the record file, not
        # a dent in the offered load.
        self.assertEqual(written + dropped, 50000)
        self.assertGreater(dropped, 0)

    def test_the_write_happens_on_a_separate_thread(self) -> None:
        # Structural proof: the file is still empty when record() returns, and
        # only fills once the writer thread has been flushed by close().
        dirobj = tempfile.TemporaryDirectory()
        self.addCleanup(dirobj.cleanup)
        path = os.path.join(dirobj.name, "requests.jsonl")

        rec = RequestRecorder(path, flush_interval_seconds=3600)
        names_before = {t.name for t in threading.enumerate()}
        self.assertIn("request-recorder", names_before,
                      "no dedicated writer thread: writes would be on the event loop")

        rec.record(time.time_ns(), "purchase", "s", "order", "GET",
                   "/api/v1/orders", 200, 1.0, "", "a" * 32, "b" * 16, True)
        self.assertEqual(os.path.getsize(path), 0,
                         "bytes hit the file synchronously with record(): the write "
                         "is on the request path")
        rec.close()
        self.assertEqual(len(read_records(path)), 1)

    def test_recording_does_not_slow_the_producer_measurably(self) -> None:
        # Timing comparison of the producer-side cost: the same loop with a
        # real recorder vs. with recording disabled (a None recorder). This is
        # the per-request tax that could eat into the offered rate.
        dirobj = tempfile.TemporaryDirectory()
        self.addCleanup(dirobj.cleanup)
        path = os.path.join(dirobj.name, "requests.jsonl")
        rec = RequestRecorder(path, buffer_records=300000, flush_interval_seconds=3600)
        self.addCleanup(rec.close)

        n = 100000
        args = (0, "purchase", "s", "order", "GET", "/api/v1/orders", 200, 1.0,
                "", "a" * 32, "b" * 16, True)

        t0 = time.monotonic()
        for _ in range(n):
            pass
        empty = time.monotonic() - t0

        t0 = time.monotonic()
        for _ in range(n):
            rec.record(*args)
        recording = time.monotonic() - t0

        per_call_us = (recording - empty) / n * 1e6
        print(f"\n  producer-side cost of recording: {per_call_us:.3f} us/request")
        # A request that takes even 1 ms would have to spend >5% of its life in
        # record() to move the offered rate. 50 us is a very loose tripwire and
        # a synchronous write (tens of us minimum, plus lock contention) or a
        # JSON encode on the producer would show up well above it.
        self.assertLess(per_call_us, 50.0,
                        f"record() costs {per_call_us:.1f} us/call on the request path")


class DurabilityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.path = os.path.join(self.dir.name, "requests.jsonl")

    def test_file_outlives_the_run_and_appends(self) -> None:
        for _ in range(2):
            rec = RequestRecorder(self.path, flush_interval_seconds=3600)
            rec.record(time.time_ns(), "c", "s", "svc", "GET", "/p", 200, 1.0,
                       "", "a" * 32, "b" * 16, True)
            rec.close()
        # O_APPEND, not truncate: a re-run must not erase the previous one.
        self.assertEqual(len(read_records(self.path)), 2)

    def test_periodic_flush_makes_the_tail_readable_during_a_run(self) -> None:
        rec = RequestRecorder(self.path, flush_interval_seconds=0.05)
        self.addCleanup(rec.close)
        rec.record(time.time_ns(), "c", "s", "svc", "GET", "/p", 200, 1.0,
                   "", "a" * 32, "b" * 16, True)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if os.path.getsize(self.path) > 0:
                return
            time.sleep(0.01)
        self.fail("record file still empty after 5s: the periodic flush is not running")

    def test_close_is_idempotent(self) -> None:
        rec = RequestRecorder(self.path, flush_interval_seconds=3600)
        rec.record(time.time_ns(), "c", "s", "svc", "GET", "/p", 200, 1.0,
                   "", "a" * 32, "b" * 16, True)
        rec.close()
        rec.close()
        self.assertEqual(len(read_records(self.path)), 1)

    def test_rotation_bounds_disk_use(self) -> None:
        # 4 KiB cap expressed in whole MB is impossible, so drive the internal
        # byte cap directly; writing 1 MiB of records would just be slow.
        rec = RequestRecorder(self.path, flush_interval_seconds=3600)
        rec._max_bytes = 2048  # noqa: SLF001 - exercising the rotation path
        for i in range(400):
            rec.record(time.time_ns(), "browse", "step", "order", "GET",
                       f"/api/v1/orders?o={i}", 200, 1.0, "",
                       "a" * 32, "b" * 16, True)
        rec.close()

        self.assertTrue(os.path.exists(self.path))
        self.assertLessEqual(os.path.getsize(self.path), 4096)
        self.assertTrue(os.path.exists(self.path + ".1"),
                        "rotated predecessor missing")
        self.assertLessEqual(len(os.listdir(self.dir.name)), 2,
                             "rotation must keep at most current + .1")

    def test_nested_directories_are_created(self) -> None:
        nested = os.path.join(self.dir.name, "a", "b", "requests.jsonl")
        rec = RequestRecorder(nested, flush_interval_seconds=3600)
        rec.record(0, "c", "s", "svc", "GET", "/p", 200, 1.0, "", "", "", True)
        rec.close()
        self.assertEqual(len(read_records(nested)), 1)

    def test_empty_path_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            RequestRecorder("   ")


class RouteTemplateTest(unittest.TestCase):
    def test_ids_collapse_and_route_elements_survive(self) -> None:
        cases = [
            ("/api/v1/orders", "/api/v1/orders"),
            ("/api/v1/orders?limit=100&offset=0", "/api/v1/orders"),
            ("/api/v1/orders/0190f0ab-1111-7000-8000-000000000001", "/api/v1/orders/{id}"),
            ("/api/v1/orders/0190f0ab-1111-7000-8000-000000000001/confirm",
             "/api/v1/orders/{id}/confirm"),
            ("/api/v1/accounts/12345/wallet", "/api/v1/accounts/{id}/wallet"),
            ("/api/v1/places", "/api/v1/places"),
            ("/api/v2/payment-channel/status", "/api/v2/payment-channel/status"),
            ("", ""),
        ]
        for raw, want in cases:
            self.assertEqual(route_template(raw), want, raw)

    def test_templating_is_stable_so_grouping_works(self) -> None:
        a = route_template("/api/v1/orders/0190f0ab-1111-7000-8000-000000000001")
        b = route_template("/api/v1/orders/0190ffff-2222-7000-8000-000000000002")
        self.assertEqual(a, b, "two ids on the same route must group together")


class TransportErrorClassificationTest(unittest.TestCase):
    def test_markers_distinguish_failure_modes(self) -> None:
        import asyncio

        self.assertEqual(classify_transport_error(asyncio.TimeoutError()), "timeout")
        self.assertEqual(classify_transport_error(ConnectionResetError()), "connection_reset")
        self.assertEqual(classify_transport_error(ConnectionRefusedError()), "connect_failed")
        self.assertEqual(classify_transport_error(asyncio.CancelledError()), "canceled")
        self.assertEqual(classify_transport_error(RuntimeError("boom")), "transport")

    def test_marker_is_always_non_empty(self) -> None:
        # status 0 rows must never carry an empty marker, or they are
        # indistinguishable from a successful request in the file.
        for exc in (Exception(), OSError(), ValueError("x"), TimeoutError()):
            self.assertNotEqual(classify_transport_error(exc), "")


class RecordingConfigTest(unittest.TestCase):
    def test_defaults_are_operational(self) -> None:
        rc = recording_config({}, "/tmp/x-requests.jsonl")
        self.assertTrue(rc["enabled"])
        self.assertEqual(rc["path"], "/tmp/x-requests.jsonl")
        self.assertGreater(rc["buffer_records"], 0,
                           "a zero buffer would drop every record")
        self.assertGreater(rc["flush_interval_seconds"], 0)
        self.assertGreater(rc["max_file_megabytes"], 0)
        # Deliberate default: the driver originates the trace, so it samples.
        self.assertEqual(rc["trace_sampled_ratio"], 1.0)

    def test_scenario_overrides_the_derived_path(self) -> None:
        rc = recording_config({"recording": {"path": "/data/custom.jsonl"}}, "/tmp/d.jsonl")
        self.assertEqual(rc["path"], "/data/custom.jsonl")

    def test_disabled_by_scenario_or_by_missing_path(self) -> None:
        self.assertFalse(recording_config({"recording": {"enabled": False}}, "/tmp/x")["enabled"])
        self.assertFalse(recording_config({}, None)["enabled"])
        self.assertIsNone(open_recorder({}, None))
        self.assertIsNone(open_recorder({"recording": {"enabled": False}}, "/tmp/x.jsonl"))

    def test_open_recorder_honours_the_config(self) -> None:
        dirobj = tempfile.TemporaryDirectory()
        self.addCleanup(dirobj.cleanup)
        path = os.path.join(dirobj.name, "r.jsonl")
        rec = open_recorder(
            {"recording": {"path": path, "buffer_records": 7,
                           "trace_sampled_ratio": 0.0, "max_file_megabytes": 3}},
            None,
        )
        self.assertIsNotNone(rec)
        self.addCleanup(rec.close)
        self.assertEqual(rec.path, path)
        self.assertEqual(rec._q.maxsize, 7)  # noqa: SLF001
        self.assertEqual(rec.trace_sampled_ratio, 0.0)
        self.assertFalse(rec.trace_sampled())

    def test_ratio_is_clamped(self) -> None:
        dirobj = tempfile.TemporaryDirectory()
        self.addCleanup(dirobj.cleanup)
        for raw, want in ((-2.0, 0.0), (5.0, 1.0), (0.3, 0.3)):
            rec = RequestRecorder(
                os.path.join(dirobj.name, f"r{raw}.jsonl"), trace_sampled_ratio=raw
            )
            self.addCleanup(rec.close)
            self.assertEqual(rec.trace_sampled_ratio, want)


class ConcurrencyTest(unittest.TestCase):
    def test_concurrent_producers_lose_nothing_with_an_adequate_buffer(self) -> None:
        dirobj = tempfile.TemporaryDirectory()
        self.addCleanup(dirobj.cleanup)
        path = os.path.join(dirobj.name, "requests.jsonl")
        rec = RequestRecorder(path, buffer_records=200000, flush_interval_seconds=3600)

        threads, each = 16, 500

        def produce(i: int) -> None:
            for _ in range(each):
                tc = new_trace_context(True)
                rec.record(time.time_ns(), "purchase", "s", "order", "GET",
                           "/api/v1/orders", 200, 1.0, "",
                           tc.trace_id, tc.span_id, True)

        ts = [threading.Thread(target=produce, args=(i,)) for i in range(threads)]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        rec.close()

        written, dropped = rec.counters()
        self.assertEqual(dropped, 0)
        self.assertEqual(written, threads * each)
        self.assertEqual(len(read_records(path)), threads * each)


def _import_driver():
    """Import driver.py, stubbing its third-party deps if they are absent.

    driver.py needs aiohttp / redis.asyncio / yaml, which are not installable
    in every environment. Skipping the integration test there would leave the
    actually-wired-up behaviour (traceparent on the wire, chain label on the
    record, transport failures recorded) untested where it matters most, so
    the missing modules are replaced by the smallest stubs that let the module
    import and let ApiClient.request run.
    """
    import sys
    import types

    if "aiohttp" not in sys.modules:
        try:
            import aiohttp  # noqa: F401
        except ImportError:
            aiohttp = types.ModuleType("aiohttp")

            class ClientTimeout:
                def __init__(self, total=None):
                    self.total = total

            class ClientSession:  # replaced per-test
                def __init__(self, *a, **kw):
                    self.closed = False

                async def request(self, *a, **kw):
                    raise NotImplementedError

                async def close(self):
                    self.closed = True

            aiohttp.ClientTimeout = ClientTimeout
            aiohttp.ClientSession = ClientSession
            sys.modules["aiohttp"] = aiohttp

    if "redis" not in sys.modules:
        try:
            import redis.asyncio  # noqa: F401
        except ImportError:
            redis = types.ModuleType("redis")
            redis_asyncio = types.ModuleType("redis.asyncio")
            redis_asyncio.from_url = lambda *a, **kw: None
            redis_asyncio.Redis = object
            redis.asyncio = redis_asyncio
            sys.modules["redis"] = redis
            sys.modules["redis.asyncio"] = redis_asyncio

    if "yaml" not in sys.modules:
        try:
            import yaml  # noqa: F401
        except ImportError:
            yaml = types.ModuleType("yaml")
            yaml.safe_load = lambda *a, **kw: {}
            yaml.safe_dump = lambda *a, **kw: ""
            sys.modules["yaml"] = yaml

    here = os.path.dirname(os.path.abspath(__file__))
    if here not in sys.path:
        sys.path.insert(0, here)
    import driver

    return driver


class FakeResponse:
    def __init__(self, status: int, payload: dict):
        self.status = status
        self._payload = payload
        self.content_length = 1

    async def json(self):
        return self._payload


class FakeSession:
    """Captures outbound headers so the recorded trace id can be compared to
    the one that actually went on the wire."""

    def __init__(self, status: int = 200, raise_exc: BaseException | None = None):
        self.closed = False
        self.status = status
        self.raise_exc = raise_exc
        self.seen: list[dict] = []

    async def request(self, method, url, json=None, headers=None):
        self.seen.append(dict(headers or {}))
        if self.raise_exc is not None:
            raise self.raise_exc
        return FakeResponse(self.status, {"ok": True})

    async def close(self):
        self.closed = True


class DriverIntegrationTest(unittest.TestCase):
    """Exercises driver.py's ApiClient recording path end to end."""

    def setUp(self) -> None:
        self.driver = _import_driver()
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.path = os.path.join(self.dir.name, "requests.jsonl")
        self.cfg = {
            "target": {"base_url_template": "http://{service}:8080"},
            "name": "t",
        }

    def _client(self, session: FakeSession):
        rec = RequestRecorder(self.path, flush_interval_seconds=3600)
        api = self.driver.ApiClient(self.cfg, self.driver.LatencyTracker(), recorder=rec)
        api._session = session  # noqa: SLF001 - bypass real aiohttp session setup
        return api, rec

    def test_driver_wires_the_recorder_into_the_api_client(self) -> None:
        d = self.driver.StressDriver(self.cfg, None, self.path)
        self.addCleanup(d.close_recorder)
        self.assertIsNotNone(d.recorder)
        self.assertIs(d.api.recorder, d.recorder)
        self.assertEqual(d.recorder.path, self.path)

    def test_recording_can_be_disabled_and_close_is_a_noop(self) -> None:
        d = self.driver.StressDriver(
            {**self.cfg, "recording": {"enabled": False}}, None, self.path
        )
        self.assertIsNone(d.recorder)
        self.assertIsNone(d.api.recorder)
        d.close_recorder()  # must be a no-op, not a crash

    def test_request_sends_traceparent_and_records_the_same_trace_id(self) -> None:
        import asyncio

        session = FakeSession(status=200)
        api, rec = self._client(session)

        async def go():
            token = self.driver.current_chain.set("purchase")
            try:
                for _ in range(15):
                    await api.request(
                        "GET", "order", "/api/v1/orders/0190f0ab-1111-7000-8000-01",
                        step="get-order", ok=(200,),
                    )
            finally:
                self.driver.current_chain.reset(token)

        asyncio.run(go())
        rec.close()

        rows = read_records(self.path)
        self.assertEqual(len(rows), 15)
        self.assertEqual(len(session.seen), 15)

        seen_ids = set()
        for hdrs, row in zip(session.seen, rows):
            self.assertIn("traceparent", hdrs,
                          "no traceparent went on the wire: the recorded id joins nothing")
            # The recorded id must be exactly the id the service received.
            self.assertEqual(
                hdrs["traceparent"], f"00-{row['trace_id']}-{row['span_id']}-01"
            )
            self.assertEqual(row["chain"], "purchase")
            self.assertEqual(row["step"], "get-order")
            self.assertEqual(row["service"], "order")
            self.assertEqual(row["status"], 200)
            self.assertEqual(row["error"], "")
            self.assertEqual(row["route"], "/api/v1/orders/{id}")
            seen_ids.add(row["trace_id"])
        self.assertEqual(len(seen_ids), 15, "each request must originate its own trace")

    def test_caller_supplied_traceparent_is_read_back_not_replaced(self) -> None:
        import asyncio

        supplied = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        session = FakeSession(status=200)
        api, rec = self._client(session)

        async def go():
            await api.request("GET", "order", "/api/v1/orders",
                              headers={"traceparent": supplied}, ok=(200,), step="list")

        asyncio.run(go())
        rec.close()

        self.assertEqual(session.seen[0]["traceparent"], supplied)
        row = read_records(self.path)[0]
        self.assertEqual(row["trace_id"], "4bf92f3577b34da6a3ce929d0e0e4736")
        self.assertEqual(row["span_id"], "00f067aa0ba902b7")

    def test_transport_failure_is_recorded_with_status_zero(self) -> None:
        import asyncio

        session = FakeSession(raise_exc=asyncio.TimeoutError())
        api, rec = self._client(session)

        async def go():
            token = self.driver.current_chain.set("refund")
            try:
                with self.assertRaises(self.driver.StepFailed):
                    await api.request("GET", "order", "/api/v1/orders",
                                      ok=(200,), step="list")
            finally:
                self.driver.current_chain.reset(token)

        asyncio.run(go())
        rec.close()

        row = read_records(self.path)[0]
        self.assertEqual(row["status"], 0,
                         "a request that never got a status must record status 0")
        self.assertEqual(row["error"], "timeout")
        self.assertEqual(row["chain"], "refund")
        self.assertEqual(len(row["trace_id"]), 32)

    def test_non_ok_status_is_still_recorded_before_raising(self) -> None:
        import asyncio

        session = FakeSession(status=500)
        api, rec = self._client(session)

        async def go():
            with self.assertRaises(self.driver.StepFailed):
                await api.request("POST", "order", "/api/v1/orders",
                                  body={"a": 1}, ok=(201,), step="create")

        asyncio.run(go())
        rec.close()

        row = read_records(self.path)[0]
        self.assertEqual(row["status"], 500)
        self.assertEqual(row["error"], "",
                         "a 500 DID get a status: the transport marker must stay empty")

    def test_aggregate_counters_are_unchanged_by_recording(self) -> None:
        import asyncio

        # The existing aggregate output must keep working exactly as before.
        session = FakeSession(status=200)
        api, rec = self._client(session)

        async def go():
            for _ in range(5):
                await api.request("GET", "order", "/api/v1/orders", ok=(200,), step="list")

        asyncio.run(go())
        rec.close()

        self.assertEqual(api.status_counts["order:200"], 5)
        self.assertEqual(sum(api.error_counts.values()), 0)
        summary = api.latency.summary()
        self.assertIn("order:GET:/api/v1/orders", summary)
        self.assertEqual(summary["order:GET:/api/v1/orders"]["count"], 5)

    def test_chain_label_is_per_task_not_global(self) -> None:
        import asyncio

        session = FakeSession(status=200)
        api, rec = self._client(session)

        async def one(chain: str):
            self.driver.current_chain.set(chain)
            await api.request("GET", "order", "/api/v1/orders", ok=(200,), step="s")

        async def go():
            # Concurrent tasks: each gets its own copy of the context, so the
            # labels must not bleed across tasks.
            await asyncio.gather(*(asyncio.create_task(one(c))
                                   for c in ("purchase", "refund", "browse")))

        asyncio.run(go())
        rec.close()

        chains = sorted(r["chain"] for r in read_records(self.path))
        self.assertEqual(chains, ["browse", "purchase", "refund"])

    def test_driver_compiles(self) -> None:
        import py_compile

        here = os.path.dirname(os.path.abspath(__file__))
        py_compile.compile(os.path.join(here, "driver.py"), doraise=True, cfile=None)


if __name__ == "__main__":
    unittest.main(verbosity=2)
