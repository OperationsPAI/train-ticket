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
import json
import math
import os
import random
import signal
import string
import sys
import time
import uuid
from collections import Counter, defaultdict, deque
from dataclasses import dataclass, field
from typing import Any

import aiohttp
import yaml

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
    """Real plan segments are seg-<uuid>; synthetic refs are rejected downstream."""
    legs = itin.get("legs") or []
    if not legs:
        return False
    ref = str(legs[0].get("serviceSegmentRef", ""))
    return ref.startswith("seg-") and len(ref) == 40 and ref.count("-") == 5


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
    def __init__(self, cfg: dict, latency: LatencyTracker):
        self.template: str = cfg["target"]["base_url_template"]
        self.timeout = aiohttp.ClientTimeout(
            total=float(cfg["target"].get("request_timeout_seconds", 15))
        )
        self.latency = latency
        self.status_counts: Counter = Counter()
        self.error_counts: Counter = Counter()
        self._session: aiohttp.ClientSession | None = None

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
            raise StepFailed(step or path, f"transport: {exc}") from exc
        finally:
            elapsed_ms = (time.monotonic() - t0) * 1000
            self.latency.record(endpoint_key, elapsed_ms)

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


class SharedState:
    def __init__(self) -> None:
        self.purchases: deque[PurchaseRef] = deque(maxlen=2000)
        self.lock = asyncio.Lock()
        # Staff work queues
        self.q_reservation: deque = deque()
        self.q_ticketing: deque = deque()

    async def add_purchase(self, p: PurchaseRef) -> None:
        async with self.lock:
            self.purchases.append(p)

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

    async def think(self) -> None:
        await asyncio.sleep(self.rng.uniform(self.think_min, self.think_max))

    async def worker(self, stop: asyncio.Event) -> None:
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

    async def do_reservation(self, item: dict) -> None:
        """Find the booking saga for an order and drive the reservation step."""
        # Poll the journey-order for a saga reference instead of Redis,
        # which is simpler for the stress driver context.
        saga = None
        for _ in range(self.poll_attempts):
            try:
                _, data = await self.api.request(
                    "GET",
                    "journey-order",
                    f"/api/v1/journey-orders/{item['order']}",
                    ok=(),
                    step="staff-poll-order",
                )
                saga = (data or {}).get("sagaId")
                if saga:
                    break
            except StepFailed:
                pass
            await asyncio.sleep(self.poll_interval)

        if not saga:
            # Fall back: try booking-orchestration list endpoint
            try:
                _, data = await self.api.request(
                    "GET",
                    "booking-orchestration",
                    f"/api/v1/internal/booking-sagas?journeyOrderId={item['order']}",
                    ok=(200,),
                    step="staff-find-saga",
                )
                sagas = data if isinstance(data, list) else data.get("items", [])
                if sagas:
                    saga = sagas[0].get("sagaId")
            except StepFailed:
                pass

        if not saga:
            raise StepFailed("reservation", f"no saga found for order {item['order']}")

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
    staff_wait = 90.0

    # 1. Identity
    account_id = f"acc-{uuid7()}"
    await api.request(
        "POST",
        "account",
        "/api/v1/accounts",
        {"accountId": account_id},
        ok=(200, 201),
        step="register-account",
    )

    given, family = rand_name(rng)
    _, tvl_data = await api.request(
        "POST",
        "traveler-profile",
        "/api/v1/travelers",
        {
            "accountId": account_id,
            "travelerType": "ADULT",
            "givenName": given,
            "familyName": family,
        },
        step="create-traveler",
    )
    traveler = tvl_data["travelerId"]

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

    # 4. Offer
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

    # 6. Reservation (staff-driven)
    resv = {"kind": "reservation", "order": order_id, "seg": seg_ref, "traveler": traveler}
    state.q_reservation.append(resv)
    sb = await _wait_for(resv, "sb", staff_wait)

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
        ok=(200, 201),
        step="payment-capture",
    )

    # 8. Ticketing (staff-driven)
    tick = {
        "kind": "ticketing",
        "order": order_id,
        "sb": sb,
        "traveler": traveler,
        "seg": seg_ref,
    }
    state.q_ticketing.append(tick)
    ent = await _wait_for(tick, "entitlement", staff_wait)

    # 9. Confirm
    final = await _poll_order(api, order_id, {"CONFIRMED"}, poll_attempts, poll_interval)
    if final != "CONFIRMED":
        results["unconfirmed"] += 1
        return "unconfirmed"

    await state.add_purchase(
        PurchaseRef(
            order_id=order_id,
            saga_id=resv.get("saga", ""),
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
    state: SharedState,
    rng: random.Random,
    results: dict,
) -> str:
    """Single refund chain: post-sales case -> evaluate -> approve."""
    p = await state.take_purchase(rng)
    if p is None:
        results["no_purchase_to_refund"] += 1
        return "no_purchase_to_refund"

    _, case = await api.request(
        "POST",
        "post-sales",
        "/api/v1/post-sales-cases",
        {
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
        },
        step="refund-case",
    )
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
    rng: random.Random,
    route: dict,
    results: dict,
) -> str:
    """Search + quote, no purchase."""
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
    def __init__(self, cfg: dict, report_path: str | None):
        self.cfg = cfg
        self.report_path = report_path
        self.latency = LatencyTracker()
        self.api = ApiClient(cfg, self.latency)
        self.state = SharedState()
        self.rng = random.Random(cfg.get("seed", {}).get("seed"))
        self.results: dict[str, int] = Counter()
        self.chain_latencies: dict[str, list[float]] = defaultdict(list)
        self.start_time = 0.0
        self.end_time = 0.0
        self.total_dispatched = 0
        self.total_errors = 0

    async def run(self) -> dict:
        load = self.cfg.get("load", {})
        model = load.get("model", "closed")
        workers = int(load.get("workers", 100))
        duration = float(load.get("duration_seconds", 120))
        rps = load.get("rps")

        routes = await resolve_routes(self.api, self.cfg, self.rng)
        if not routes:
            return self._build_report()

        mix = self.cfg.get("mix", {"purchase": 1.0})
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
                self._open_loop_dispatch(bucket, sem, stop, routes, norm_mix)
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
                    self._closed_loop_worker(i, stop, routes, norm_mix)
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
        t0 = time.monotonic()
        try:
            if chain_type == "purchase":
                await purchase_chain(
                    self.api, self.cfg, self.state, self.rng, route, self.results
                )
            elif chain_type == "refund":
                await refund_chain(self.api, self.state, self.rng, self.results)
            elif chain_type == "browse":
                await browse_chain(self.api, self.rng, route, self.results)
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

    report_path = args.report
    if report_path is None:
        name = cfg.get("name", "stress")
        report_path = f"/tmp/{name}-report.json"

    driver = StressDriver(cfg, report_path)

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

    # Print summary
    print("\n" + "=" * 60, flush=True)
    print(f"Scenario:    {report.get('scenario')}", flush=True)
    print(f"Duration:    {report.get('duration_seconds')}s", flush=True)
    print(f"Dispatched:  {report.get('total_dispatched')}", flush=True)
    print(f"Errors:      {report.get('total_errors')}", flush=True)
    print(f"Eff. RPS:    {report.get('effective_rps')}", flush=True)
    print(f"Results:     {json.dumps(report.get('results', {}), indent=2)}", flush=True)
    print(f"Report:      {report_path}", flush=True)
    print("=" * 60, flush=True)


def main() -> None:
    asyncio.run(async_main())


if __name__ == "__main__":
    main()
