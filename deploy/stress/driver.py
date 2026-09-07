#!/usr/bin/env python3
"""Open-loop stress test driver for the train-ticket microservice system.

Usage:
  python3 driver.py --scenario scenarios/s1-rush.yaml --report /tmp/s1-report.json
  python3 driver.py --scenario scenarios/s2-staircase.yaml --rps 50 --duration 120

Key differences from the loadgen:
  - Open-loop: a token bucket controls the arrival rate independently of
    how fast (or slow) the system responds.
  - Per-endpoint latency histograms with p50/p95/p99/max.
  - Scenario YAML drives the load profile (rush, staircase, etc.).
  - Machine-readable JSON report output for the auditor.
"""

from __future__ import annotations

import argparse
import asyncio
import contextvars
import hashlib
import json
import math
import os
import random
import re
import signal
import string
import sys
import time
import uuid
from collections import Counter, defaultdict, deque
from dataclasses import asdict, dataclass, fields
from typing import Any

import aiohttp
import redis.asyncio as aioredis
import yaml

from request_records import (
    classify_transport_error,
    new_trace_context,
    open_recorder,
    parse_traceparent,
    recorder_trace_sampled,
)

# ---------------------------------------------------------------------------
# Chain labelling for per-request records (issue #420).
#
# ApiClient is a single shared object, so the chain name cannot live on it. A
# ContextVar rides the asyncio task instead: each task created by the
# dispatcher gets its own copy of the context, so a chain label set inside
# _run_chain applies to exactly the requests that chain makes, with no
# plumbing through every chain function's signature.
# ---------------------------------------------------------------------------

current_chain: contextvars.ContextVar[str] = contextvars.ContextVar(
    "current_chain", default="unlabelled"
)

# ---------------------------------------------------------------------------
# Utilities (compatible with loadgen conventions)
# ---------------------------------------------------------------------------


def uuid7() -> str:
    """Generate a UUID v7 (time-ordered) as used by the greenfield services."""
    ts = int(time.time() * 1000)
    rand_a = random.getrandbits(12)
    rand_b = random.getrandbits(62)
    value = (ts << 80) | (0x7 << 76) | (rand_a << 64) | (0b10 << 62) | rand_b
    return str(uuid.UUID(int=value))


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def rand_name(rng: random.Random) -> tuple[str, str]:
    given = rng.choice(
        ["Wei", "Fang", "Min", "Jing", "Lei", "Yan", "Hao", "Xin", "Tao", "Mei"]
    )
    family = rng.choice(
        ["Zhang", "Wang", "Li", "Zhao", "Chen", "Liu", "Yang", "Huang", "Zhou", "Wu"]
    )
    suffix = "".join(rng.choices(string.ascii_lowercase, k=4))
    return given, f"{family}-{suffix}"


def _bookable(itin: dict) -> bool:
    """Real plan segments start with seg-; synthetic/empty refs are rejected."""
    legs = itin.get("legs") or []
    if not legs:
        return False
    ref = str(legs[0].get("serviceSegmentRef", ""))
    return ref.startswith("seg-") and len(ref) > 4


# ---------------------------------------------------------------------------
# Token bucket — open-loop arrival control
# ---------------------------------------------------------------------------


class TokenBucket:
    """Precise rate limiter: acquire() suspends until a token is available."""

    def __init__(self, rate: float, burst: int = 1):
        self._rate = max(rate, 0.001)
        self._burst = max(burst, 1)
        self._tokens = float(burst)
        self._last = time.monotonic()
        self._lock = asyncio.Lock()

    @property
    def rate(self) -> float:
        return self._rate

    def set_rate(self, rate: float) -> None:
        self._rate = max(rate, 0.001)

    async def acquire(self) -> None:
        async with self._lock:
            now = time.monotonic()
            elapsed = now - self._last
            self._tokens = min(self._tokens + elapsed * self._rate, float(self._burst))
            self._last = now
            if self._tokens >= 1.0:
                self._tokens -= 1.0
                return
            wait = (1.0 - self._tokens) / self._rate
            self._tokens = 0.0
        await asyncio.sleep(wait)


# ---------------------------------------------------------------------------
# Per-endpoint latency tracking
# ---------------------------------------------------------------------------


class LatencyTracker:
    """Accumulates per-endpoint latency samples and computes percentiles."""

    def __init__(self, cap: int = 50_000):
        self._data: dict[str, list[float]] = defaultdict(list)
        self._cap = cap

    def record(self, endpoint: str, ms: float) -> None:
        buf = self._data[endpoint]
        buf.append(ms)
        if len(buf) > self._cap:
            del buf[: len(buf) - self._cap]

    def _pct(self, sorted_vals: list[float], p: float) -> float:
        idx = max(0, int(math.ceil(len(sorted_vals) * p)) - 1)
        return round(sorted_vals[idx], 2)

    def summary(self) -> dict[str, dict[str, Any]]:
        out: dict[str, dict[str, Any]] = {}
        for ep, vals in self._data.items():
            if not vals:
                continue
            s = sorted(vals)
            out[ep] = {
                "count": len(s),
                "p50": self._pct(s, 0.50),
                "p95": self._pct(s, 0.95),
                "p99": self._pct(s, 0.99),
                "max": round(s[-1], 2),
            }
        return out


# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------


class StepFailed(Exception):
    def __init__(self, step: str, detail: str):
        super().__init__(f"{step}: {detail}")
        self.step = step


# ---------------------------------------------------------------------------
# HTTP client wrapper
# ---------------------------------------------------------------------------


class ApiClient:
    def __init__(self, cfg: dict, latency: LatencyTracker, recorder: Any = None):
        self.template: str = cfg["target"]["base_url_template"]
        self.timeout = aiohttp.ClientTimeout(
            total=float(cfg["target"].get("request_timeout_seconds", 15))
        )
        self.latency = latency
        self.status_counts: Counter = Counter()
        self.error_counts: Counter = Counter()
        self._session: aiohttp.ClientSession | None = None
        # None when per-request recording is disabled (issue #420).
        self.recorder = recorder

    async def session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(timeout=self.timeout)
        return self._session

    async def request(
        self,
        method: str,
        service: str,
        path: str,
        body: dict | None = None,
        headers: dict | None = None,
        ok: tuple[int, ...] = (200, 201),
        step: str = "",
    ) -> tuple[int, Any]:
        url = self.template.format(service=service) + path
        hdrs = dict(headers or {})
        if method in ("POST", "PUT", "PATCH") and "Idempotency-Key" not in hdrs:
            hdrs["Idempotency-Key"] = uuid7()
        endpoint_key = f"{service}:{method}:{path.split('?')[0]}"

        # W3C trace context. The driver originates the trace, so it mints the
        # traceparent rather than waiting for an instrumented client; the id it
        # puts on the wire is the id it records, which is what makes a recorded
        # row joinable to the server spans in Jaeger. A caller-supplied header
        # wins and is read back instead.
        if "traceparent" in hdrs:
            tc = parse_traceparent(hdrs["traceparent"])
        else:
            tc = new_trace_context(recorder_trace_sampled(self.recorder))
            hdrs["traceparent"] = tc.header

        rec = self.recorder
        start_ns = time.time_ns()
        t0 = time.monotonic()
        try:
            sess = await self.session()
            resp = await sess.request(method, url, json=body, headers=hdrs)
            status = resp.status
            try:
                data = await resp.json() if resp.content_length != 0 else {}
            except Exception:
                data = {}
        except Exception as exc:
            self.error_counts[f"{service}:transport"] += 1
            if rec is not None:
                # status 0 + a non-empty error is the "never got a status"
                # marker. These rows are the most interesting in the file:
                # they are invisible in the service:status counters.
                rec.record(
                    start_ns,
                    current_chain.get(),
                    step,
                    service,
                    method,
                    path,
                    0,
                    (time.monotonic() - t0) * 1000,
                    classify_transport_error(exc),
                    tc.trace_id,
                    tc.span_id,
                    tc.sampled,
                )
            failure_step = f"{step}-transport" if step else f"{path}-transport"
            raise StepFailed(failure_step, f"transport: {exc}") from exc
        finally:
            elapsed_ms = (time.monotonic() - t0) * 1000
            self.latency.record(endpoint_key, elapsed_ms)

        if rec is not None:
            rec.record(
                start_ns,
                current_chain.get(),
                step,
                service,
                method,
                path,
                status,
                elapsed_ms,
                "",
                tc.trace_id,
                tc.span_id,
                tc.sampled,
            )

        self.status_counts[f"{service}:{status}"] += 1
        if ok and status not in ok:
            self.error_counts[f"{service}:{status}"] += 1
            raise StepFailed(
                step or path, f"{method} {service}{path} -> {status} {str(data)[:200]}"
            )
        return status, data

    async def close(self) -> None:
        if self._session and not self._session.closed:
            await self._session.close()


# ---------------------------------------------------------------------------
# Shared state for purchase references (needed by refund chain)
# ---------------------------------------------------------------------------


@dataclass
class PurchaseRef:
    order_id: str
    saga_id: str
    sb_id: str
    segment_ref: str
    traveler_ref: str
    account_id: str
    entitlement_id: str
    total_minor: int


@dataclass
class IdentityRef:
    account_id: str
    traveler_id: str

class SharedState:
    def __init__(self, redis_url: str = "redis://redis:6379") -> None:
        self.purchases: deque[PurchaseRef] = deque(maxlen=2000)
        self.all_purchases: list[PurchaseRef] = []
        self.lock = asyncio.Lock()
        self.q_reservation: deque = deque()
        self.q_ticketing: deque = deque()
        self._redis_url = redis_url
        self._redis: Any = None
        self.identity_pool: list[IdentityRef] = []
        self._identity_idx = 0
        self._identity_lock = asyncio.Lock()
        self._refund_attempt_idx = 0

    async def redis_conn(self) -> Any:
        if self._redis is None:
            self._redis = aioredis.from_url(self._redis_url, decode_responses=True)
        return self._redis

    async def next_identity(self) -> IdentityRef | None:
        if not self.identity_pool:
            return None
        async with self._identity_lock:
            ref = self.identity_pool[self._identity_idx % len(self.identity_pool)]
            self._identity_idx += 1
            return ref

    async def add_purchase(self, p: PurchaseRef, record: bool = True) -> None:
        async with self.lock:
            self.purchases.append(p)
            if record:
                self.all_purchases.append(p)

    async def purchase_count(self) -> int:
        async with self.lock:
            return len(self.purchases)

    async def next_refund_attempt_index(self) -> int:
        async with self.lock:
            self._refund_attempt_idx += 1
            return self._refund_attempt_idx

    async def take_purchase(self, rng: random.Random) -> PurchaseRef | None:
        async with self.lock:
            if not self.purchases:
                return None
            idx = rng.randrange(len(self.purchases))
            p = self.purchases[idx]
            del self.purchases[idx]
            return p


# ---------------------------------------------------------------------------
# Staff simulator (minimal — drives reservation + ticketing queues)
# ---------------------------------------------------------------------------


class StaffSim:
    """Processes staff work queues: reservation driving and ticket issuing."""

    def __init__(
        self, cfg: dict, api: ApiClient, state: SharedState, rng: random.Random
    ):
        staff_cfg = cfg.get("staff", {})
        self.api = api
        self.state = state
        self.rng = rng
        self.think_min = float(staff_cfg.get("think_time_seconds", {}).get("min", 0.2))
        self.think_max = float(staff_cfg.get("think_time_seconds", {}).get("max", 1.0))
        self.queue_poll = float(staff_cfg.get("queue_poll_seconds", 0.3))
        self.poll_attempts = int(cfg.get("polling", {}).get("attempts", 10))
        self.poll_interval = float(cfg.get("polling", {}).get("interval_seconds", 3))
        self.redis_url = cfg["target"].get("redis_url", "redis://redis:6379")
        self._redis: aioredis.Redis | None = None
        self.redis_url = cfg["target"].get("redis_url", "redis://redis:6379")

    async def think(self) -> None:
        await asyncio.sleep(self.rng.uniform(self.think_min, self.think_max))

    async def worker(self, stop: asyncio.Event) -> None:
        current_chain.set("staff")
        while not stop.is_set():
            item = None
            for q in (self.state.q_reservation, self.state.q_ticketing):
                try:
                    item = q.popleft()
                    break
                except IndexError:
                    continue
            if item is None:
                await asyncio.sleep(self.queue_poll)
                continue
            try:
                await self.think()
                handler = getattr(self, f"do_{item['kind']}", None)
                if handler:
                    await handler(item)
            except Exception as exc:
                item["failed"] = True
                item["error"] = f"{type(exc).__name__}: {exc}"

    async def _redis_conn(self) -> aioredis.Redis:
        if self._redis is None:
            self._redis = aioredis.from_url(self.redis_url, decode_responses=True)
        return self._redis

    async def do_reservation(self, item: dict) -> None:
        """Find the booking saga via Redis stream, then drive reservation."""
        saga = None
        order_id = item["order"]

        r = await self._redis_conn()
        for _ in range(self.poll_attempts):
            try:
                entries = await r.xrevrange("events:booking-orchestration", count=500)
                for _mid, fields in entries:
                    raw = fields.get("envelope", "")
                    if "BookingSagaStarted" in raw and order_id in raw:
                        env = json.loads(raw)
                        if (env.get("eventType") == "BookingSagaStarted"
                                and env.get("payload", {}).get("journeyOrderId") == order_id):
                            saga = env["payload"]["sagaId"]
                            break
                if saga:
                    break
            except Exception:
                pass
            await asyncio.sleep(1)

        if not saga:
            raise StepFailed("reservation", f"no saga found for order {order_id}")

        sb = f"sb-{uuid7()}"
        await self.api.request(
            "POST",
            "booking-orchestration",
            f"/api/v1/internal/booking-sagas/{saga}/request-reservation",
            {
                "segmentRef": item["seg"],
                "travelerRef": item["traveler"],
                "segmentBookingId": sb,
            },
            ok=(200,),
            step="staff-reservation",
        )
        item["saga"] = saga
        item["sb"] = sb

    async def do_ticketing(self, item: dict) -> None:
        _, data = await self.api.request(
            "POST",
            "entitlement-ticketing",
            "/api/v1/entitlements",
            {
                "segmentBookingId": item["sb"],
                "journeyOrderId": item["order"],
                "travelerRef": item["traveler"],
                "segmentRef": item["seg"],
                "issuePurpose": "INITIAL",
            },
            step="staff-ticketing",
        )
        item["entitlement"] = data.get("entitlementId", "")


# ---------------------------------------------------------------------------
# Chain executors
# ---------------------------------------------------------------------------


async def _wait_for(item: dict, key: str, timeout: float) -> Any:
    """Poll a staff work item until the expected key is filled."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if item.get("failed"):
            raise StepFailed(
                item.get("kind", "staff"), str(item.get("error", ""))[:200]
            )
        if item.get(key) is not None:
            return item[key]
        await asyncio.sleep(0.5)
    raise StepFailed(item.get("kind", "staff"), f"timed out waiting for {key}")


async def _poll_order(
    api: ApiClient,
    order_id: str,
    want: set[str],
    attempts: int,
    interval: float,
) -> str | None:
    status = None
    for _ in range(attempts):
        try:
            code, data = await api.request(
                "GET",
                "journey-order",
                f"/api/v1/journey-orders/{order_id}",
                ok=(),
                step="poll-order",
            )
            if code == 200:
                status = (data or {}).get("status")
                if status in want:
                    return status
        except StepFailed:
            pass
        await asyncio.sleep(interval)
    return status


async def purchase_chain(
    api: ApiClient,
    cfg: dict,
    state: SharedState,
    rng: random.Random,
    route: dict,
    results: dict,
) -> str:
    """Single purchase chain: search -> quote -> offer -> order -> reserve -> pay -> ticket."""
    poll_attempts = int(cfg.get("polling", {}).get("attempts", 10))
    poll_interval = float(cfg.get("polling", {}).get("interval_seconds", 3))
    staff_wait = 30.0

    # 1. Identity (pre-created during StressDriver bootstrap)
    identity = await state.next_identity()
    if identity is None:
        raise StepFailed("identity-pool", "no pre-created identities available")
    account_id = identity.account_id
    traveler = identity.traveler_id

    # 2. Search
    _, search_data = await api.request(
        "POST",
        "trip-planning",
        "/api/v1/itineraries/search",
        {
            "originRef": route["origin"],
            "destinationRef": route["destination"],
            "departureDate": route["date"],
            "travelerRefs": [traveler],
            "channel": "WEB",
        },
        ok=(200,),
        step="search",
    )
    itins = [i for i in (search_data.get("itineraries") or []) if _bookable(i)]
    if not itins:
        results["no_inventory"] += 1
        return "no_inventory"
    itin = rng.choice(itins) if len(itins) > 1 else itins[0]
    itin_ref = itin["itineraryRef"]
    seg_ref = itin["legs"][0]["serviceSegmentRef"]

    # 3. Quote
    await api.request(
        "POST",
        "fare-pricing",
        "/api/v1/fare-quotes",
        {
            "travelerRefs": [traveler],
            "channel": "WEB",
            "segmentRefs": [seg_ref],
        },
        step="quote",
    )
    await asyncio.sleep(1)

    # 4. Offer (retry on 422 — quote event may not be consumed yet)
    offer = None
    for _offer_attempt in range(10):
        try:
            _, offer = await api.request(
                "POST",
                "offer-management",
                "/api/v1/offers",
                {
                    "accountId": account_id,
                    "channelId": "WEB",
                    "itineraryRef": itin_ref,
                    "travelerRefs": [traveler],
                },
                step="offer",
            )
            break
        except StepFailed as exc:
            if "422" not in str(exc) or _offer_attempt == 9:
                raise
            await asyncio.sleep(min(1.0 * (1.5 ** _offer_attempt), 5.0))

    # 5. Order
    _, order = await api.request(
        "POST",
        "journey-order",
        "/api/v1/journey-orders",
        {
            "accountId": account_id,
            "offerId": offer["offerId"],
            "offerVersion": offer.get("offerVersion", 1),
            "travelerRefs": [traveler],
            "segmentRefs": [seg_ref],
        },
        step="order",
    )
    order_id = order["orderId"]

    # 6. Reservation (inline — saga discovery + direct API call)
    saga = None
    r = await state.redis_conn()
    for _saga_attempt in range(poll_attempts):
        try:
            entries = await r.xrevrange("events:booking-orchestration", count=500)
            for _mid, fields in entries:
                raw = fields.get("envelope", "")
                if "BookingSagaStarted" in raw and order_id in raw:
                    env = json.loads(raw)
                    if (env.get("eventType") == "BookingSagaStarted"
                            and env.get("payload", {}).get("journeyOrderId") == order_id):
                        saga = env["payload"]["sagaId"]
                        break
            if saga:
                break
        except Exception:
            pass
        await asyncio.sleep(poll_interval)
    if not saga:
        raise StepFailed("reservation", f"no saga for {order_id}")
    sb = f"sb-{uuid7()}"
    await api.request(
        "POST", "booking-orchestration",
        f"/api/v1/internal/booking-sagas/{saga}/request-reservation",
        {"segmentRef": seg_ref, "travelerRef": traveler, "segmentBookingId": sb},
        ok=(200,), step="reservation",
    )
    await asyncio.sleep(2)

    # 7. Payment
    total_minor = int(offer.get("total", {}).get("minorUnits", 10750))
    _, intent = await api.request(
        "POST",
        "payment",
        "/api/v1/payment-intents",
        {
            "businessRef": order_id,
            "purpose": "purchase",
            "amount": {"currency": "CNY", "minorUnits": total_minor},
            "payerRef": account_id,
        },
        step="payment-intent",
    )
    await api.request(
        "POST",
        "payment",
        f"/api/v1/payment-intents/{intent['paymentIntentId']}/capture",
        {},
        ok=(200, 201, 202),
        step="payment-capture",
    )
    await asyncio.sleep(3)

    # 8. Ticketing (inline — direct API call)
    _, ent_data = await api.request(
        "POST", "entitlement-ticketing", "/api/v1/entitlements",
        {"segmentBookingId": sb, "journeyOrderId": order_id,
         "travelerRef": traveler, "segmentRef": seg_ref, "issuePurpose": "INITIAL"},
        step="ticketing",
    )
    ent = ent_data.get("entitlementId", "")

    # 9. Confirm (accept CONFIRMING — all steps done, risk event still propagating)
    final = await _poll_order(api, order_id, {"CONFIRMED", "CONFIRMING"}, poll_attempts, poll_interval)
    if final not in ("CONFIRMED", "CONFIRMING"):
        results["unconfirmed"] += 1
        return "unconfirmed"

    await state.add_purchase(
        PurchaseRef(
            order_id=order_id,
            saga_id=saga or "",
            sb_id=sb,
            segment_ref=seg_ref,
            traveler_ref=traveler,
            account_id=account_id,
            entitlement_id=ent,
            total_minor=total_minor,
        )
    )
    results["purchased"] += 1
    return "purchased"


async def refund_chain(
    api: ApiClient,
    cfg: dict,
    state: SharedState,
    rng: random.Random,
    results: dict,
) -> str:
    """Single refund chain: post-sales case -> evaluate -> approve."""
    p = await state.take_purchase(rng)
    if p is None:
        if cfg.get("seed", {}).get("purchase_report"):
            results["refund_pool_exhausted"] += 1
            return "refund_pool_exhausted"
        results["no_purchase_to_refund"] += 1
        return "no_purchase_to_refund"

    case_body = {
        "journeyOrderId": p.order_id,
        "caseType": "REFUND",
        "scope": {
            "orderItemRefs": [p.sb_id],
            "segmentRefs": [p.segment_ref],
            "travelerRefs": [p.traveler_ref],
            "entitlementRefs": [p.entitlement_id],
        },
        "reasonCode": "CUSTOMER_REQUEST",
        "actorRef": p.account_id,
    }
    case_key = uuid7()
    _, case = await api.request(
        "POST",
        "post-sales",
        "/api/v1/post-sales-cases",
        case_body,
        headers={"Idempotency-Key": case_key},
        step="refund-case",
    )

    duplicate_probability = float(
        cfg.get("constraints", {}).get("p_duplicate_refund", 0.0) or 0.0
    )
    refund_attempt_idx = await state.next_refund_attempt_index()
    duplicate_every = (
        int(round(1.0 / duplicate_probability)) if duplicate_probability > 0 else 0
    )
    if duplicate_every > 0 and refund_attempt_idx % duplicate_every == 0:
        await api.request(
            "POST",
            "post-sales",
            "/api/v1/post-sales-cases",
            case_body,
            headers={"Idempotency-Key": case_key},
            ok=(200, 201),
            step="refund-case-duplicate",
        )
        results["duplicate_refund_submitted"] += 1
    case_id = case.get("caseId") or case.get("postSalesCaseId")
    if not case_id:
        raise StepFailed("refund-case", f"no case id: {str(case)[:150]}")

    await api.request(
        "POST",
        "post-sales",
        f"/api/v1/post-sales-cases/{case_id}/evaluate",
        {},
        ok=(200, 201),
        step="refund-evaluate",
    )
    await api.request(
        "POST",
        "post-sales",
        f"/api/v1/post-sales-cases/{case_id}/approve",
        {},
        ok=(200, 201),
        step="refund-approve",
    )

    results["refunded"] += 1
    return "refunded"


async def browse_chain(
    api: ApiClient,
    state: SharedState,
    rng: random.Random,
    route: dict,
    results: dict,
) -> str:
    """Search + quote, no purchase."""
    identity = await state.next_identity()
    if identity:
        account_id = identity.account_id
        traveler = identity.traveler_id
    else:
        account_id = f"acc-{uuid7()}"
        await api.request(
            "POST", "account", "/api/v1/accounts",
            {"accountId": account_id}, ok=(200, 201), step="register-account",
        )
        given, family = rand_name(rng)
        _, tvl_data = await api.request(
            "POST", "traveler-profile", "/api/v1/travelers",
            {"accountId": account_id, "travelerType": "ADULT",
             "givenName": given, "familyName": family}, step="create-traveler",
        )
        traveler = tvl_data["travelerId"]

    _, search_data = await api.request(
        "POST", "trip-planning", "/api/v1/itineraries/search",
        {"originRef": route["origin"], "destinationRef": route["destination"],
         "departureDate": route["date"], "travelerRefs": [traveler], "channel": "WEB"},
        ok=(200,), step="search",
    )
    itins = [i for i in (search_data.get("itineraries") or []) if _bookable(i)]
    if itins:
        seg_ref = itins[0]["legs"][0]["serviceSegmentRef"]
        await api.request(
            "POST", "fare-pricing", "/api/v1/fare-quotes",
            {"travelerRefs": [traveler], "channel": "WEB",
             "segmentRefs": [seg_ref]}, step="quote",
        )

    results["browsed"] += 1
    return "browsed"


# ---------------------------------------------------------------------------
# Staircase controller
# ---------------------------------------------------------------------------


async def staircase_controller(
    cfg: dict, bucket: TokenBucket, stop: asyncio.Event
) -> None:
    """Adjust the token bucket rate according to the staircase profile."""
    steps = cfg.get("load", {}).get("staircase")
    if not steps:
        return
    for step in steps:
        if stop.is_set():
            return
        rps = float(step["rps"])
        hold = float(step["hold_seconds"])
        bucket.set_rate(rps)
        print(f"[staircase] RPS -> {rps}, holding {hold}s", flush=True)
        try:
            await asyncio.wait_for(stop.wait(), timeout=hold)
            return
        except asyncio.TimeoutError:
            pass


# ---------------------------------------------------------------------------
# Route resolution from seed config
# ---------------------------------------------------------------------------


async def resolve_routes(
    api: ApiClient, cfg: dict, rng: random.Random
) -> list[dict]:
    """Discover bookable routes from the seed config via the place/search APIs."""
    seed = cfg.get("seed", {})
    cities = seed.get("cities", ["Beijing", "Shanghai"])
    date = seed.get("departure_date", "2026-09-01")
    constraints = cfg.get("constraints", {})
    target_segments = constraints.get("target_segments")

    # Discover place IDs
    _, listing = await api.request(
        "GET", "place-network", "/api/v1/places?limit=100&offset=0&status=ACTIVE",
        ok=(200,), step="list-places",
    )
    by_name: dict[str, str] = {}
    for p in listing.get("items", []):
        name = p.get("canonicalName", "")
        by_name[name] = p["placeId"]

    routes: list[dict] = []
    place_ids = []
    for city in cities:
        pid = by_name.get(city)
        if pid:
            place_ids.append(pid)

    if len(place_ids) < 2:
        print(
            f"[routes] only {len(place_ids)} cities resolved; "
            "using all place pairs as fallback",
            flush=True,
        )
        place_ids = [p["placeId"] for p in listing.get("items", []) if p.get("placeId")]

    # Probe search to find bookable pairs
    probe_tvl = None
    for a in place_ids:
        for b in place_ids:
            if a == b:
                continue
            try:
                if probe_tvl is None:
                    _, t = await api.request(
                        "POST", "traveler-profile", "/api/v1/travelers",
                        {"accountId": f"acc-{uuid7()}", "travelerType": "ADULT",
                         "givenName": "Probe", "familyName": "Driver"},
                        step="probe-traveler",
                    )
                    probe_tvl = t["travelerId"]
                _, res = await api.request(
                    "POST", "trip-planning", "/api/v1/itineraries/search",
                    {"originRef": a, "destinationRef": b,
                     "departureDate": date, "travelerRefs": [probe_tvl],
                     "channel": "WEB"}, ok=(200,), step="probe-search",
                )
                if any(_bookable(i) for i in res.get("itineraries") or []):
                    routes.append({"origin": a, "destination": b, "date": date})
                    if target_segments and len(routes) >= target_segments:
                        return routes
            except StepFailed:
                continue

    if not routes:
        print("[routes] WARNING: no bookable routes found; chains will fail", flush=True)
    else:
        print(f"[routes] discovered {len(routes)} bookable route(s)", flush=True)
    return routes


# ---------------------------------------------------------------------------
# Main driver
# ---------------------------------------------------------------------------


class StressDriver:
    def __init__(self, cfg: dict, report_path: str | None, records_path: str | None = None):
        self.cfg = cfg
        self.report_path = report_path
        self.latency = LatencyTracker()
        # Per-request outcome records (issue #420). None when disabled; the
        # aggregate report below is unchanged either way.
        self.recorder = open_recorder(cfg, records_path)
        self.api = ApiClient(cfg, self.latency, recorder=self.recorder)
        self.state = SharedState(redis_url=cfg["target"].get("redis_url", "redis://redis:6379"))
        self.routes: list[dict] = []
        self.rng = random.Random(cfg.get("seed", {}).get("seed"))
        self.results: dict[str, int] = Counter()
        self.chain_latencies: dict[str, list[float]] = defaultdict(list)
        self.start_time = 0.0
        self.end_time = 0.0
        self.total_dispatched = 0
        self.total_errors = 0
        self.seed_purchases_loaded = 0

    async def _load_purchases_from_report(self, path: str) -> None:
        with open(path) as f:
            report = json.load(f)
        purchases = report.get("purchases") or []
        if not isinstance(purchases, list):
            raise StepFailed("seed-purchase-report", "purchases must be a list")

        required = {field.name for field in fields(PurchaseRef)}
        loaded = 0
        for idx, raw in enumerate(purchases):
            if not isinstance(raw, dict):
                raise StepFailed("seed-purchase-report", f"purchase {idx} is not an object")
            missing = sorted(required.difference(raw))
            if missing:
                raise StepFailed(
                    "seed-purchase-report",
                    f"purchase {idx} missing field(s): {', '.join(missing)}",
                )
            await self.state.add_purchase(
                PurchaseRef(**{name: raw[name] for name in required}),
                record=False,
            )
            loaded += 1
        self.seed_purchases_loaded = loaded
        self.results["seed_purchases_loaded"] += loaded
        print(f"[bootstrap] loaded {loaded} purchase ref(s) from {path}", flush=True)

    async def _create_identity(self, idx: int) -> IdentityRef:
        acct_id = f"acc-{uuid7()}"
        given, family = rand_name(random.Random(idx))
        await self.api.request(
            "POST",
            "account",
            "/api/v1/accounts",
            {"accountId": acct_id},
            ok=(200, 201),
            step="bootstrap-account",
        )
        _, tvl = await self.api.request(
            "POST",
            "traveler-profile",
            "/api/v1/travelers",
            {
                "accountId": acct_id,
                "travelerType": "ADULT",
                "givenName": given,
                "familyName": family,
            },
            step="bootstrap-traveler",
        )
        traveler = tvl["travelerId"]

        # sim-tail-v1 accepts documents whose final digit is 0..5. Rotate those
        # passing tails while keeping every account/traveler/document distinct.
        tail = str(idx % 6)
        doc_hash = (
            hashlib.sha256(f"stress-{traveler}-{tail}".encode()).hexdigest() + tail
        )
        name_hash = hashlib.sha256(("name-" + traveler).encode()).hexdigest()
        valid_until = "2027-07-10T00:00:00Z"
        material_fingerprint = hashlib.sha256(
            "|".join(
                [name_hash, "ID_CARD", doc_hash, "", valid_until, "", "stress-v1"]
            ).encode()
        ).hexdigest()

        _, cred = await self.api.request(
            "POST",
            "identity-verification",
            "/api/v1/identity-verification/credentials",
            {
                "travelerId": traveler,
                "profileSnapshotVersion": "stress-v1",
                "documentType": "ID_CARD",
                "maskedDocumentNo": f"ST***{tail}",
                "documentHash": doc_hash,
                "canonicalNameHash": name_hash,
                "validUntil": valid_until,
            },
            ok=(200, 201),
            step="bootstrap-credential",
        )
        await self.api.request(
            "POST",
            "identity-verification",
            "/api/v1/identity-verification/verification-cases",
            {
                "travelerId": traveler,
                "credentialRecordId": cred["credentialRecordId"],
                "purpose": "ORDER_CREATION",
                "materialFingerprint": material_fingerprint,
                "simPolicyVersion": "sim-tail-v1",
                "requestedAt": now_iso(),
            },
            ok=(200, 201),
            step="bootstrap-verify",
        )
        return IdentityRef(account_id=acct_id, traveler_id=traveler)

    async def _bootstrap_identity_pool(self, pool_size: int) -> None:
        print(f"[bootstrap] pre-creating {pool_size} identities...", flush=True)
        for idx in range(pool_size):
            try:
                self.state.identity_pool.append(await self._create_identity(idx))
            except StepFailed as exc:
                raise StepFailed(
                    "bootstrap-identity-pool",
                    f"identity {idx} failed: {exc}",
                ) from exc
        print(
            f"[bootstrap] {len(self.state.identity_pool)} identities ready",
            flush=True,
        )

    def close_recorder(self) -> None:
        """Drain, flush and close the per-request record file.

        Idempotent, so every exit path from run() can call it. Reports the
        written/dropped counts on their own line -- the aggregate report JSON
        is deliberately left untouched.
        """
        if self.recorder is None:
            return
        self.recorder.close()
        written, dropped = self.recorder.counters()
        print(
            f"[records] {self.recorder.path}: {written} records written, {dropped} dropped",
            flush=True,
        )
        if dropped:
            print(
                f"[records] WARNING {dropped} records dropped (buffer full). "
                "Offered load was unaffected -- dropping is the deliberate trade -- "
                "but the record file is incomplete; raise recording.buffer_records "
                "or use faster storage.",
                file=sys.stderr,
                flush=True,
            )

    async def run(self) -> dict:
        load = self.cfg.get("load", {})
        model = load.get("model", "closed")
        workers = int(load.get("workers", 100))
        duration = float(load.get("duration_seconds", 120))
        rps = load.get("rps")

        mix = self.cfg.get("mix", {"purchase": 1.0})
        if float(mix.get("refund", 0)) > 0:
            purchase_report = self.cfg.get("seed", {}).get("purchase_report")
            if purchase_report:
                await self._load_purchases_from_report(purchase_report)

        needs_routes = (
            float(mix.get("purchase", 0)) > 0
            or float(mix.get("browse", 0)) > 0
        )
        current_chain.set("bootstrap")
        routes = await resolve_routes(self.api, self.cfg, self.rng) if needs_routes else []
        self.routes = routes
        if needs_routes and not routes:
            # Aborting before any load: still flush what bootstrap recorded,
            # since those requests are exactly why route resolution failed.
            self.close_recorder()
            return self._build_report()

        if float(mix.get("purchase", 0)) > 0:
            await self._bootstrap_identity_pool(workers * 3)
        current_chain.set("unlabelled")

        # Normalize mix weights
        total_weight = sum(float(v) for v in mix.values() if float(v) > 0)
        if total_weight == 0:
            total_weight = 1.0
        norm_mix = {k: float(v) / total_weight for k, v in mix.items() if float(v) > 0}

        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, stop.set)
        if duration > 0:
            loop.call_later(duration, stop.set)

        # Staff workers
        staff_cfg = self.cfg.get("staff", {})
        staff_count = int(staff_cfg.get("workers", 2))
        staff_sim = StaffSim(self.cfg, self.api, self.state, self.rng)
        staff_tasks = [
            asyncio.create_task(staff_sim.worker(stop)) for _ in range(staff_count)
        ]

        self.start_time = time.time()

        if model == "open" and rps:
            bucket = TokenBucket(rate=float(rps), burst=max(1, int(float(rps) / 2)))
            sem = asyncio.Semaphore(workers)

            # Start staircase controller if configured
            staircase_task = asyncio.create_task(
                staircase_controller(self.cfg, bucket, stop)
            )

            dispatch_task = asyncio.create_task(
                self._open_loop_dispatch(bucket, sem, stop, routes or [{}], norm_mix)
            )
            await stop.wait()
            dispatch_task.cancel()
            staircase_task.cancel()
            # Let in-flight chains drain (bounded by semaphore)
            await asyncio.sleep(2)
        else:
            # Closed-loop: fixed number of workers, each fires as fast as possible
            chain_tasks = [
                asyncio.create_task(
                    self._closed_loop_worker(i, stop, routes or [{}], norm_mix)
                )
                for i in range(workers)
            ]
            await stop.wait()
            await asyncio.gather(*chain_tasks, return_exceptions=True)

        self.end_time = time.time()

        # Clean up staff
        stop.set()
        await asyncio.gather(*staff_tasks, return_exceptions=True)
        await self.api.close()

        # Drain and close the record file after the last in-flight chain, so
        # records buffered during shutdown still land. Done before the report
        # is written so the two artifacts describe the same set of requests.
        self.close_recorder()

        report = self._build_report()
        if self.report_path:
            with open(self.report_path, "w") as f:
                json.dump(report, f, indent=2)
            print(f"[report] written to {self.report_path}", flush=True)

        return report

    async def _open_loop_dispatch(
        self,
        bucket: TokenBucket,
        sem: asyncio.Semaphore,
        stop: asyncio.Event,
        routes: list[dict],
        mix: dict[str, float],
    ) -> None:
        """Dispatch chains at the token-bucket rate, bounded by the semaphore."""
        while not stop.is_set():
            await bucket.acquire()
            if stop.is_set():
                break
            await sem.acquire()
            chain_type = self._pick_chain(mix)
            route = self.rng.choice(routes)
            asyncio.create_task(
                self._run_chain_with_sem(sem, chain_type, route)
            )

    async def _run_chain_with_sem(
        self, sem: asyncio.Semaphore, chain_type: str, route: dict
    ) -> None:
        try:
            await self._run_chain(chain_type, route)
        finally:
            sem.release()

    async def _closed_loop_worker(
        self,
        idx: int,
        stop: asyncio.Event,
        routes: list[dict],
        mix: dict[str, float],
    ) -> None:
        while not stop.is_set():
            chain_type = self._pick_chain(mix)
            if (
                chain_type == "refund"
                and self.seed_purchases_loaded > 0
                and await self.state.purchase_count() == 0
            ):
                stop.set()
                return
            route = self.rng.choice(routes)
            await self._run_chain(chain_type, route)

    def _pick_chain(self, mix: dict[str, float]) -> str:
        r = self.rng.random()
        cumulative = 0.0
        for name, weight in mix.items():
            cumulative += weight
            if r <= cumulative:
                return name
        return list(mix.keys())[-1]

    async def _run_chain(self, chain_type: str, route: dict) -> None:
        self.total_dispatched += 1
        # Tag every request this chain makes, so the record file can be split
        # by chain. Set inside the task, so it affects only this task's
        # context copy.
        current_chain.set(chain_type)
        t0 = time.monotonic()
        try:
            if chain_type == "purchase":
                await purchase_chain(
                    self.api, self.cfg, self.state, self.rng, route, self.results
                )
            elif chain_type == "refund":
                await refund_chain(self.api, self.cfg, self.state, self.rng, self.results)
            elif chain_type == "browse":
                await browse_chain(self.api, self.state, self.rng, route, self.results)
            else:
                self.results[f"unknown_chain:{chain_type}"] += 1
        except StepFailed as exc:
            self.total_errors += 1
            self.results[f"failed:{chain_type}:{exc.step}"] += 1
        except Exception as exc:
            self.total_errors += 1
            self.results[f"crashed:{chain_type}:{type(exc).__name__}"] += 1
        finally:
            elapsed_ms = (time.monotonic() - t0) * 1000
            self.chain_latencies[chain_type].append(elapsed_ms)

    def _build_report(self) -> dict:
        elapsed = max(self.end_time - self.start_time, 0.001)

        chain_summary: dict[str, Any] = {}
        for chain_type, vals in self.chain_latencies.items():
            if not vals:
                continue
            s = sorted(vals)
            chain_summary[chain_type] = {
                "count": len(s),
                "p50_ms": round(s[len(s) // 2], 2),
                "p95_ms": round(s[max(0, int(len(s) * 0.95) - 1)], 2),
                "p99_ms": round(s[max(0, int(len(s) * 0.99) - 1)], 2),
                "max_ms": round(s[-1], 2),
            }

        return {
            "scenario": self.cfg.get("name", "unknown"),
            "timestamp": now_iso(),
            "duration_seconds": round(elapsed, 2),
            "total_dispatched": self.total_dispatched,
            "total_errors": self.total_errors,
            "effective_rps": round(self.total_dispatched / elapsed, 2),
            "results": dict(self.results),
            "chain_latencies": chain_summary,
            "endpoint_latencies": self.latency.summary(),
            "http_status_counts": dict(self.api.status_counts),
            "error_counts": dict(self.api.error_counts),
            "route_count": len(self.routes),
            "seed_purchases_loaded": self.seed_purchases_loaded,
            "purchases_remaining": len(self.state.purchases),
            "purchases": [asdict(p) for p in self.state.all_purchases],
        }


# ---------------------------------------------------------------------------
# Periodic stats printer
# ---------------------------------------------------------------------------


async def periodic_stats(driver: StressDriver, stop: asyncio.Event) -> None:
    while not stop.is_set():
        try:
            await asyncio.wait_for(stop.wait(), timeout=15.0)
        except asyncio.TimeoutError:
            pass
        elapsed = time.time() - driver.start_time if driver.start_time else 0
        print(
            f"[stats] t={elapsed:.0f}s dispatched={driver.total_dispatched} "
            f"errors={driver.total_errors} results={dict(driver.results)}",
            flush=True,
        )


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Open-loop stress test driver for train-ticket"
    )
    parser.add_argument(
        "--scenario", required=True, help="Path to scenario YAML file"
    )
    parser.add_argument(
        "--report", default=None, help="Path to write JSON report"
    )
    parser.add_argument(
        "--rps", type=float, default=None, help="Override RPS from scenario"
    )
    parser.add_argument(
        "--duration",
        type=float,
        default=None,
        help="Override duration_seconds from scenario",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=None,
        help="Override worker count from scenario",
    )
    parser.add_argument(
        "--records",
        default=None,
        help="Path to write per-request JSON Lines records (one row per client "
             "HTTP request). Defaults to <report-path-without-.json>-requests.jsonl. "
             "Use --no-records to disable, or set recording.path in the scenario.",
    )
    parser.add_argument(
        "--no-records",
        dest="records",
        action="store_const",
        const="",
        help="Disable per-request record output (aggregates are unaffected)",
    )
    parser.add_argument(
        "--purchase-report",
        "--seed-report",
        dest="purchase_report",
        default=None,
        help="Driver JSON report containing purchase refs to refund",
    )
    return parser.parse_args()


async def async_main() -> None:
    args = parse_args()

    with open(args.scenario) as f:
        cfg = yaml.safe_load(f)

    # CLI overrides
    if args.rps is not None:
        cfg.setdefault("load", {})["rps"] = args.rps
        cfg["load"]["model"] = "open"
    if args.duration is not None:
        cfg.setdefault("load", {})["duration_seconds"] = args.duration
    if args.workers is not None:
        cfg.setdefault("load", {})["workers"] = args.workers
    if args.purchase_report is not None:
        cfg.setdefault("seed", {})["purchase_report"] = args.purchase_report

    report_path = args.report
    if report_path is None:
        name = cfg.get("name", "stress")
        report_path = f"/tmp/{name}-report.json"

    # Per-request records (issue #420) sit next to the aggregate report by
    # default, so a run always produces both without an extra flag.
    # --records "" (i.e. --no-records) turns them off; a scenario's
    # recording.path overrides the default.
    if args.records is None:
        records_path = re.sub(r"\.json$", "", report_path) + "-requests.jsonl"
    elif args.records == "":
        records_path = None
        cfg.setdefault("recording", {})["enabled"] = False
    else:
        records_path = args.records

    driver = StressDriver(cfg, report_path, records_path)

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)

    stats_task = asyncio.create_task(periodic_stats(driver, stop))

    try:
        report = await driver.run()
    finally:
        stop.set()
        stats_task.cancel()
        # Idempotent; this is the safety net for an exception mid-run, so a
        # crashed scenario still leaves a readable record file behind.
        driver.close_recorder()

    # Print summary
    print("\n" + "=" * 60, flush=True)
    print(f"Scenario:    {report.get('scenario')}", flush=True)
    print(f"Duration:    {report.get('duration_seconds')}s", flush=True)
    print(f"Dispatched:  {report.get('total_dispatched')}", flush=True)
    print(f"Errors:      {report.get('total_errors')}", flush=True)
    print(f"Eff. RPS:    {report.get('effective_rps')}", flush=True)
    print(f"Results:     {json.dumps(report.get('results', {}), indent=2)}", flush=True)
    print(f"Report:      {report_path}", flush=True)
    if driver.recorder is not None:
        written, dropped = driver.recorder.counters()
        print(
            f"Records:     {driver.recorder.path} ({written} rows"
            + (f", {dropped} dropped" if dropped else "")
            + ")",
            flush=True,
        )
    print("=" * 60, flush=True)


def main() -> None:
    asyncio.run(async_main())


if __name__ == "__main__":
    main()
