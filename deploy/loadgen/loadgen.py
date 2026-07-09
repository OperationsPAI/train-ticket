#!/usr/bin/env python3
"""Actor-based load generator for the train-ticket system.

Two simulated actor pools, mirroring reality:

- CUSTOMERS use only public HTTP APIs. Every parameter is discovered
  dynamically (list/search endpoints), created fresh (mock identities), or
  reused from the persistent registry ("existing" entities, re-validated via
  GET before use). Where the real system needs staff/platform action, the
  customer enqueues a work item and waits — like a real user watching a
  spinner.

- STAFF workers process those queues the way platform choreography and
  human agents would: drive saga reservations, issue tickets, review
  risk-blocked orders, work support cases. Staff have their own concurrency,
  think times, and decision probabilities.

All distributions are hyperparameters in config.yaml — nothing is hardcoded.
"""

from __future__ import annotations

import asyncio
import json
import os
import random
import signal
import string
import time
import uuid
from collections import Counter, defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any
from urllib.parse import quote

import httpx
import redis.asyncio as aioredis
import yaml

# ---------------------------------------------------------------------------
# utilities
# ---------------------------------------------------------------------------


def uuid7() -> str:
    ts = int(time.time() * 1000)
    rand_a = random.getrandbits(12)
    rand_b = random.getrandbits(62)
    value = (ts << 80) | (0x7 << 76) | (rand_a << 64) | (0b10 << 62) | rand_b
    return str(uuid.UUID(int=value))


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def weighted_choice(rng: random.Random, weights: dict[str, float]) -> str:
    items = [(k, float(v)) for k, v in weights.items() if float(v) > 0]
    total = sum(w for _, w in items)
    x = rng.uniform(0, total)
    for k, w in items:
        x -= w
        if x <= 0:
            return k
    return items[-1][0]


def rand_name(rng: random.Random) -> tuple[str, str]:
    given = rng.choice(["Wei", "Fang", "Min", "Jing", "Lei", "Yan", "Hao", "Xin", "Tao", "Mei"])
    family = rng.choice(["Zhang", "Wang", "Li", "Zhao", "Chen", "Liu", "Yang", "Huang", "Zhou", "Wu"])
    return given, f"{family}-{''.join(rng.choices(string.ascii_lowercase, k=4))}"


def _bookable(itin: dict) -> bool:
    """Real plan segments are seg-<uuid>; trip-planning falls back to
    synthetic refs (seg-web-<date>-<hash>) that downstream services reject."""
    legs = itin.get("legs") or []
    if not legs:
        return False
    ref = str(legs[0].get("serviceSegmentRef", ""))
    return ref.startswith("seg-") and len(ref) == 40 and ref.count("-") == 5


class Abandoned(Exception):
    """Customer walked away on purpose — an outcome, not an error."""


class StepFailed(Exception):
    def __init__(self, step: str, detail: str):
        super().__init__(f"{step}: {detail}")
        self.step = step


# ---------------------------------------------------------------------------
# stats
# ---------------------------------------------------------------------------


class Stats:
    def __init__(self) -> None:
        self.journeys: Counter[str] = Counter()
        self.staff: Counter[str] = Counter()
        self.http: Counter[str] = Counter()
        self.errors: Counter[str] = Counter()
        self.latency_ms: dict[str, list[float]] = defaultdict(list)
        self.started = time.time()

    def record_http(self, service: str, status: int, ms: float) -> None:
        self.http[f"{service}:{status}"] += 1
        buf = self.latency_ms[service]
        buf.append(ms)
        if len(buf) > 5000:
            del buf[: len(buf) - 5000]

    def snapshot(self) -> dict[str, Any]:
        lat = {}
        for svc, values in self.latency_ms.items():
            if values:
                s = sorted(values)
                lat[svc] = {"n": len(s), "p50": round(s[len(s) // 2], 1),
                            "p95": round(s[max(0, int(len(s) * 0.95) - 1)], 1), "max": round(s[-1], 1)}
        return {
            "uptime_s": round(time.time() - self.started, 1),
            "journeys": dict(self.journeys),
            "staff_actions": dict(self.staff),
            "http": dict(self.http),
            "errors": dict(self.errors),
            "latency_ms": lat,
        }


# ---------------------------------------------------------------------------
# API client
# ---------------------------------------------------------------------------


class Api:
    def __init__(self, cfg: dict, stats: Stats):
        self.template = cfg["target"]["base_url_template"]
        self.stats = stats
        self.client = httpx.AsyncClient(timeout=float(cfg["target"].get("request_timeout_seconds", 10)))

    async def request(self, method: str, service: str, path: str, body: dict | None = None,
                      headers: dict | None = None, ok: tuple[int, ...] = (200, 201),
                      step: str = "") -> tuple[int, Any]:
        url = self.template.format(service=service) + path
        hdrs = dict(headers or {})
        if method in ("POST", "PUT", "PATCH") and "Idempotency-Key" not in hdrs:
            hdrs["Idempotency-Key"] = uuid7()
        t0 = time.time()
        try:
            resp = await self.client.request(method, url, json=body, headers=hdrs)
        except httpx.HTTPError as exc:
            self.stats.errors[f"{service}:transport"] += 1
            raise StepFailed(step or path, f"transport: {exc}") from exc
        self.stats.record_http(service, resp.status_code, (time.time() - t0) * 1000)
        try:
            data = resp.json() if resp.content else {}
        except json.JSONDecodeError:
            data = {}
        if ok and resp.status_code not in ok:
            self.stats.errors[f"{service}:{resp.status_code}"] += 1
            raise StepFailed(step or path, f"{method} {service}{path} -> {resp.status_code} {str(data)[:150]}")
        return resp.status_code, data

    async def close(self) -> None:
        await self.client.aclose()


# ---------------------------------------------------------------------------
# registry: persisted entities + runtime staff work queues
# ---------------------------------------------------------------------------


@dataclass
class Purchase:
    order: str
    saga: str
    sb: str
    seg: str
    traveler: str
    account: str
    entitlement: str
    total_minor: int
    status: str = "confirmed"
    offer: str = ""
    payment_intent: str = ""
    itinerary: str = ""
    quote: str = ""
    post_sales_case: str = ""
    fulfillment_record: str = ""


@dataclass
class WaitlistRef:
    waitlist_request_id: str
    account: str
    traveler: str
    seg: str
    payment_intent: str
    intent_fingerprint: str
    status: str = "QUEUED"


@dataclass
class Registry:
    accounts: list[dict] = field(default_factory=list)
    purchases: list[Purchase] = field(default_factory=list)
    waitlists: list[WaitlistRef] = field(default_factory=list)
    routes: list[dict] = field(default_factory=list)
    ops_entities: dict[str, list[str]] = field(default_factory=lambda: {"suppliers": [], "carriers": [], "contracts": []})
    lock: asyncio.Lock = field(default_factory=asyncio.Lock, repr=False)
    # staff work queues (runtime only, never persisted)
    q_reservation: deque = field(default_factory=deque, repr=False)
    q_ticketing: deque = field(default_factory=deque, repr=False)
    q_risk: deque = field(default_factory=deque, repr=False)
    q_support: deque = field(default_factory=deque, repr=False)
    q_dispatch: deque = field(default_factory=deque, repr=False)

    @classmethod
    def load(cls, path: str) -> "Registry":
        reg = cls()
        if path and os.path.exists(path):
            try:
                raw = json.load(open(path))
                reg.accounts = raw.get("accounts", [])
                reg.purchases = [Purchase(**p) for p in raw.get("purchases", [])]
                reg.waitlists = []
                for w in raw.get("waitlists", []):
                    if "account" not in w:
                        w["account"] = ""
                    reg.waitlists.append(WaitlistRef(**w))
                reg.routes = raw.get("routes", [])
                reg.ops_entities = raw.get("ops_entities", reg.ops_entities)
            except Exception as exc:
                print(f"[registry] ignoring unreadable state file: {exc}")
        return reg

    def save(self, path: str) -> None:
        if not path:
            return
        tmp = path + ".tmp"
        with open(tmp, "w") as fh:
            json.dump({"accounts": self.accounts,
                       "purchases": [vars(p) for p in self.purchases],
                       "waitlists": [vars(w) for w in self.waitlists],
                       "routes": self.routes,
                       "ops_entities": self.ops_entities}, fh)
        os.replace(tmp, path)

    async def pick_account(self, rng: random.Random) -> dict | None:
        async with self.lock:
            return rng.choice(self.accounts) if self.accounts else None

    async def add_account(self, account_id: str) -> dict:
        async with self.lock:
            entry = {"account_id": account_id, "travelers": []}
            self.accounts.append(entry)
            if len(self.accounts) > 500:
                self.accounts.pop(0)
            return entry

    async def add_purchase(self, p: Purchase) -> None:
        async with self.lock:
            self.purchases.append(p)
            if len(self.purchases) > 1000:
                self.purchases.pop(0)

    async def take_purchase(self, rng: random.Random, status: str = "confirmed") -> Purchase | None:
        async with self.lock:
            candidates = [p for p in self.purchases if p.status == status]
            if not candidates:
                return None
            p = rng.choice(candidates)
            p.status = "consumed"
            return p

    async def release_purchase(self, p: Purchase, status: str) -> None:
        async with self.lock:
            p.status = status

    async def add_waitlist(self, w: WaitlistRef) -> None:
        async with self.lock:
            self.waitlists.append(w)
            if len(self.waitlists) > 500:
                self.waitlists.pop(0)

    async def remember_ops_entity(self, kind: str, entity_id: str) -> None:
        async with self.lock:
            bucket = self.ops_entities.setdefault(kind, [])
            bucket.append(entity_id)
            if len(bucket) > 200:
                bucket.pop(0)


async def wait_for(item: dict, key: str, timeout: float, poll: float = 1.0) -> Any:
    """Customer-side wait: poll a work item until staff filled `key`."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if item.get("failed"):
            raise StepFailed(item.get("kind", "staff"), str(item.get("error"))[:150])
        if item.get(key) is not None:
            return item[key]
        await asyncio.sleep(poll)
    raise StepFailed(item.get("kind", "staff"), f"timed out waiting for {key}")


# ---------------------------------------------------------------------------
# staff simulator — the platform/agent side, one actor pool like customers
# ---------------------------------------------------------------------------


class StaffSim:
    def __init__(self, cfg: dict, api: Api, reg: Registry, stats: Stats, rng: random.Random):
        self.cfg = cfg["staff"]
        self.api = api
        self.reg = reg
        self.stats = stats
        self.rng = rng
        self.redis = aioredis.from_url(cfg["target"]["redis_url"], decode_responses=True)
        self.poll_attempts = int(cfg["polling"]["attempts"])
        self.poll_interval = float(cfg["polling"]["interval_seconds"])

    async def think(self) -> None:
        t = self.cfg["think_time_seconds"]
        await asyncio.sleep(self.rng.uniform(float(t["min"]), float(t["max"])))

    async def worker(self, idx: int, stop: asyncio.Event) -> None:
        while not stop.is_set():
            item = None
            for q in (self.reg.q_reservation, self.reg.q_ticketing, self.reg.q_risk, self.reg.q_support, self.reg.q_dispatch):
                if q:
                    item = q.popleft()
                    break
            if item is None:
                await asyncio.sleep(float(self.cfg.get("queue_poll_seconds", 0.5)))
                continue
            try:
                await self.think()
                await getattr(self, "do_" + item["kind"])(item)
                self.stats.staff[item["kind"]] += 1
            except Exception as exc:
                item["failed"] = True
                item["error"] = f"{type(exc).__name__}: {exc}"
                self.stats.staff[f"{item['kind']}:failed"] += 1
                print(f"[staff{idx}] {item['kind']} failed — {str(exc)[:180]}")

    # -- duties ------------------------------------------------------------

    async def do_reservation(self, item: dict) -> None:
        """Find the booking saga for an order and drive its reservation step."""
        saga = None
        for _ in range(self.poll_attempts):
            entries = await self.redis.xrevrange("events:booking-orchestration", count=200)
            for _id, fields in entries:
                raw = fields.get("envelope")
                if not raw:
                    continue
                try:
                    env = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                if env.get("eventType") == "BookingSagaStarted" and \
                        env.get("payload", {}).get("journeyOrderId") == item["order"]:
                    saga = env["payload"]["sagaId"]
                    break
            if saga:
                break
            await asyncio.sleep(self.poll_interval)
        if not saga:
            raise StepFailed("reservation", f"no BookingSagaStarted for {item['order']}")
        sb = f"sb-{uuid7()}"
        await self.api.request(
            "POST", "booking-orchestration",
            f"/api/v1/internal/booking-sagas/{saga}/request-reservation",
            {"segmentRef": item["seg"], "travelerRef": item["traveler"], "segmentBookingId": sb},
            ok=(200,), step="staff-reservation")
        item["saga"] = saga
        if await self.saga_failed_no_capacity(saga):
            item["no_capacity"] = True
        item["sb"] = sb

    async def saga_failed_no_capacity(self, saga: str) -> bool:
        for _ in range(self.poll_attempts):
            code, data = await self.api.request(
                "GET", "booking-orchestration", f"/api/v1/internal/booking-sagas/{saga}",
                ok=(), step="staff-poll-reservation")
            if code == 200:
                text = json.dumps({
                    "status": data.get("status"),
                    "terminalReason": data.get("terminalReason"),
                    "steps": data.get("steps", []),
                })
                if "NO_AVAILABLE_CAPACITY" in text:
                    return True
                if data.get("status") in {"WAITING_PAYMENT", "HELD", "TICKETING", "COMPLETED"}:
                    return False
            await asyncio.sleep(self.poll_interval)
        return False

    async def do_ticketing(self, item: dict) -> None:
        _, data = await self.api.request(
            "POST", "entitlement-ticketing", "/api/v1/entitlements",
            {"segmentBookingId": item["sb"], "journeyOrderId": item["order"],
             "travelerRef": item["traveler"], "segmentRef": item["seg"], "issuePurpose": "INITIAL"},
            step="staff-ticketing")
        item["entitlement"] = data["entitlementId"]

    async def do_risk(self, item: dict) -> None:
        """Manual risk review: approve (lift) or reject per hyperparameter."""
        if self.rng.random() < float(self.cfg["p_risk_approve"]):
            await self.api.request(
                "POST", "risk-compliance", "/api/v1/risk-blocks/lift",
                {"subjectRef": item["order"], "scope": "ORDER", "reasonCode": "MANUAL_REVIEW_CLEARED"},
                ok=(200, 201, 409), step="staff-risk-lift")
            item["risk"] = "lifted"
        else:
            item["risk"] = "rejected"


    async def do_dispatch(self, item: dict) -> None:
        ride = item["ride"]
        branch = item.get("branch", "complete")
        await self.api.request("POST", "dispatch", f"/api/v1/ride-requests/{quote(ride)}/assign",
                               {"driverRef": f"drv-{uuid7()}", "vehicleRef": f"veh-{uuid7()}", "etaSeconds": self.rng.randint(30, 300)},
                               ok=(200,), step="staff-dispatch-assign")
        if branch == "driver_cancel_reassign":
            await self.api.request("POST", "dispatch", f"/api/v1/ride-requests/{quote(ride)}/driver-cancel",
                                   {"reason": "DRIVER_UNAVAILABLE"}, ok=(200,), step="staff-dispatch-driver-cancel")
            await self.api.request("POST", "dispatch", f"/api/v1/ride-requests/{quote(ride)}/assign",
                                   {"driverRef": f"drv-{uuid7()}", "vehicleRef": f"veh-{uuid7()}", "etaSeconds": self.rng.randint(30, 300)},
                                   ok=(200,), step="staff-dispatch-reassign")
        await self.api.request("POST", "dispatch", f"/api/v1/ride-requests/{quote(ride)}/eta",
                               {"etaSeconds": self.rng.randint(10, 120)}, ok=(200,), step="staff-dispatch-eta")
        await self.api.request("POST", "dispatch", f"/api/v1/ride-requests/{quote(ride)}/driver-arrived",
                               {}, ok=(200,), step="staff-dispatch-arrived")
        if branch == "no_show":
            await self.api.request("POST", "dispatch", f"/api/v1/ride-requests/{quote(ride)}/no-show",
                                   {"reason": "RIDER_ABSENT"}, ok=(200,), step="staff-dispatch-no-show")
            item["dispatch"] = "no_show"
            return
        await self.api.request("POST", "dispatch", f"/api/v1/ride-requests/{quote(ride)}/start",
                               {}, ok=(200,), step="staff-dispatch-start")
        await self.api.request("POST", "dispatch", f"/api/v1/ride-requests/{quote(ride)}/complete",
                               {"finalFareRef": f"fare-final-{uuid7()}"}, ok=(200,), step="staff-dispatch-complete")
        item["dispatch"] = "driver_cancel_reassigned_completed" if branch == "driver_cancel_reassign" else "completed"

    async def do_support(self, item: dict) -> None:
        case_id = item["case"]
        requester = item.get("requester", "tvl-loadgen")
        branch = weighted_choice(self.rng, {
            "assign_resolve": float(self.cfg.get("p_support_assign_resolve_branch", 0.70)),
            "classify_escalate": float(self.cfg.get("p_support_classify_escalate_branch", 0.15)),
            "classify_close": float(self.cfg.get("p_support_classify_close_branch", 0.15)),
        })
        if branch == "assign_resolve" and self.rng.random() < float(self.cfg["p_support_assign"]):
            await self.api.request(
                "POST", "customer-service", f"/api/v1/support-cases/{case_id}/assign",
                {"ownerQueue": "tier1"}, ok=(200, 201), step="staff-support-assign")
            item["support"] = "assigned"
            if self.rng.random() < float(self.cfg["p_support_resolve"]):
                await self.think()
                await self.api.request(
                    "POST", "customer-service", f"/api/v1/support-cases/{case_id}/resolve",
                    {"summary": "Handled by simulated tier1 agent", "resolutionCode": "POST_SALES_EXPLAINED"},
                    ok=(200, 201), step="staff-support-resolve")
                item["support"] = "resolved"
        else:
            await self.api.request(
                "POST", "customer-service", f"/api/v1/support-cases/{case_id}/classify",
                {"classification": "POST_SALES_HELP", "priority": "NORMAL"},
                ok=(200, 201), step="staff-support-classify")
            await self.think()
            if branch == "classify_escalate":
                await self.api.request(
                    "POST", "customer-service", f"/api/v1/support-cases/{case_id}/assign",
                    {"ownerQueue": "tier1"}, ok=(200, 201), step="staff-support-assign-before-escalate")
                await self.api.request(
                    "POST", "customer-service", f"/api/v1/support-cases/{case_id}/escalate",
                    {"targetQueue": "tier2", "reason": "loadgen long-tail escalation"},
                    ok=(200, 201), step="staff-support-escalate")
                item["support"] = "escalated"
            else:
                await self.api.request(
                    "POST", "customer-service", f"/api/v1/support-cases/{case_id}/close",
                    {"reason": "NO_FURTHER_ACTION"}, ok=(200, 201), step="staff-support-close")
                item["support"] = "closed"
                if self.rng.random() < float(self.cfg.get("p_support_reopen_after_close", 0.10)):
                    await self.think()
                    await self.api.request(
                        "POST", "customer-service", f"/api/v1/support-cases/{case_id}/reopen",
                        {"reason": "customer supplied more context", "requesterRef": requester},
                        ok=(200, 201), step="staff-support-reopen")
                    await self.think()
                    await self.api.request(
                        "POST", "customer-service", f"/api/v1/support-cases/{case_id}/close",
                        {"reason": "NO_FURTHER_ACTION"}, ok=(200, 201), step="staff-support-close-again")
                    item["support"] = "reopened_closed"
        if "support" not in item:
            item["support"] = "queued"

    async def close(self) -> None:
        await self.redis.aclose()


# ---------------------------------------------------------------------------
# customer simulator — public APIs only; waits on staff where reality does
# ---------------------------------------------------------------------------


class CustomerSim:
    def __init__(self, cfg: dict, api: Api, reg: Registry, stats: Stats, rng: random.Random):
        self.cfg = cfg
        self.api = api
        self.reg = reg
        self.stats = stats
        self.rng = rng
        self.b = cfg["behavior"]
        self.poll_attempts = int(cfg["polling"]["attempts"])
        self.poll_interval = float(cfg["polling"]["interval_seconds"])
        self.staff_wait = float(cfg["behavior"].get("staff_wait_seconds", 90))
        self.long_tail = cfg.get("long_tail", {})
        self.wallet_cfg = cfg.get("wallet_promotion", {})
        self.redis = aioredis.from_url(cfg["target"]["redis_url"], decode_responses=True)

    async def think(self) -> None:
        t = self.cfg["run"]["think_time_seconds"]
        await asyncio.sleep(self.rng.uniform(float(t["min"]), float(t["max"])))

    def chance(self, key: str) -> bool:
        return self.rng.random() < float(self.b[key])

    def optional_chance(self, key: str, default: float = 0.0) -> bool:
        return self.rng.random() < float(self.b.get(key, default))

    # -- identity ------------------------------------------------------------

    async def login_or_register(self) -> dict:
        entry = None
        if not self.chance("p_new_account"):
            entry = await self.reg.pick_account(self.rng)
        if entry is not None:
            code, _ = await self.api.request("GET", "account", f"/api/v1/accounts/{entry['account_id']}",
                                             ok=(), step="login")
            if code == 200:
                return entry
        account_id = f"acc-{uuid7()}"
        await self.api.request("POST", "account", "/api/v1/accounts", {"accountId": account_id},
                               ok=(200, 201), step="register-account")
        return await self.reg.add_account(account_id)

    async def obtain_traveler(self, entry: dict, exclude: tuple = ()) -> str:
        # journey-order rejects duplicate travelerRefs within one order, so
        # multi-traveler journeys exclude already-picked travelers from reuse.
        pool = [t for t in entry["travelers"] if t not in exclude]
        if pool and not self.chance("p_new_traveler"):
            tvl = self.rng.choice(pool)
            code, _ = await self.api.request("GET", "traveler-profile", f"/api/v1/travelers/{tvl}",
                                             ok=(), step="get-traveler")
            if code == 200:
                return tvl
        given, family = rand_name(self.rng)
        _, data = await self.api.request(
            "POST", "traveler-profile", "/api/v1/travelers",
            {"accountId": entry["account_id"],
             "travelerType": weighted_choice(self.rng, self.b["traveler_types"]),
             "givenName": given, "familyName": family},
            step="create-traveler")
        tvl = data["travelerId"]
        entry["travelers"].append(tvl)
        if len(entry["travelers"]) > 20:
            entry["travelers"].pop(0)
        return tvl

    # -- inventory -------------------------------------------------------

    async def pick_route(self) -> dict:
        async with self.reg.lock:
            routes = list(self.reg.routes)
        if not routes:
            raise StepFailed("pick-route", "no known routes (bootstrap disabled or failed)")
        return self.rng.choice(routes)

    async def search(self, route: dict, travelers: list[str], channel: str) -> dict:
        _, data = await self.api.request(
            "POST", "trip-planning", "/api/v1/itineraries/search",
            {"originRef": route["origin_place"], "destinationRef": route["dest_place"],
             "departureDate": route["date"], "travelerRefs": travelers, "channel": channel},
            ok=(200,), step="search")
        itins = [i for i in (data.get("itineraries") or []) if _bookable(i)]
        if not itins:
            raise StepFailed("search", f"no bookable itinerary for {route['date']}")
        itin = self.rng.choice(itins)
        leg = itin["legs"][0]
        return {"itinerary": itin["itineraryRef"], "segment": leg["serviceSegmentRef"],
                "service": leg.get("servicePlanRef") or route.get("scheduled_service"),
                "origin_node": leg.get("originStopRef") or route.get("origin_node"),
                "dest_node": leg.get("destinationStopRef") or route.get("dest_node")}

    # -- journeys ----------------------------------------------------------

    async def journey_browse(self) -> str:
        entry = await self.login_or_register()
        tvl = await self.obtain_traveler(entry)
        channel = weighted_choice(self.rng, self.b["channels"])
        found = await self.search(await self.pick_route(), [tvl], channel)
        await self.think()
        if self.chance("p_abandon_after_search"):
            return "browsed"
        _, quote = await self.api.request("POST", "fare-pricing", "/api/v1/fare-quotes",
                                          {"travelerRefs": [tvl], "channel": channel,
                                           "segmentRefs": [found["segment"]]}, step="quote")
        await self.maybe_read_probe({"quote": quote.get("quoteId"), "itinerary": found.get("itinerary")})
        return "browsed_with_quote"

    async def journey_purchase(self) -> str:
        entry = await self.login_or_register()
        travelers = [await self.obtain_traveler(entry)]
        if self.chance("p_second_traveler"):
            travelers.append(await self.obtain_traveler(entry, exclude=tuple(travelers)))
        channel = weighted_choice(self.rng, self.b["channels"])
        route = await self.pick_route()

        found = await self.search(route, travelers, channel)
        await self.think()
        if self.chance("p_abandon_after_search"):
            raise Abandoned()

        _, quote = await self.api.request("POST", "fare-pricing", "/api/v1/fare-quotes",
                                          {"travelerRefs": travelers, "channel": channel,
                                           "segmentRefs": [found["segment"]]}, step="quote")
        await self.think()
        if self.chance("p_abandon_after_quote"):
            raise Abandoned()

        _, offer = await self.api.request(
            "POST", "offer-management", "/api/v1/offers",
            {"accountId": entry["account_id"], "channelId": channel,
             "itineraryRef": found["itinerary"], "travelerRefs": travelers}, step="offer")
        _, order = await self.api.request(
            "POST", "journey-order", "/api/v1/journey-orders",
            {"accountId": entry["account_id"], "offerId": offer["offerId"],
             "offerVersion": offer.get("offerVersion", 1),
             "travelerRefs": travelers, "segmentRefs": [found["segment"]]}, step="order")
        order_id = order["orderId"]

        # risk gate — a blocked order goes to the staff risk queue
        status = await self.poll_order(order_id, {"CONFIRMED"}, give_up_on_block=True)
        if status is not None and "BLOCK" in status:
            review = {"kind": "risk", "order": order_id}
            self.reg.q_risk.append(review)
            verdict = await wait_for(review, "risk", self.staff_wait)
            if verdict != "lifted":
                return "risk_rejected"

        # reservation is driven by staff/platform choreography — enqueue & wait
        resv = {"kind": "reservation", "order": order_id,
                "seg": found["segment"], "traveler": travelers[0]}
        self.reg.q_reservation.append(resv)
        sb = await wait_for(resv, "sb", self.staff_wait)

        await self.think()
        total_minor = int(offer["total"]["minorUnits"])
        _, intent = await self.api.request(
            "POST", "payment", "/api/v1/payment-intents",
            {"businessRef": order_id, "purpose": "purchase",
             "amount": {"currency": "CNY", "minorUnits": total_minor},
             "payerRef": entry["account_id"]}, step="payment-intent")
        common_refs = {
            "order": order_id, "account": entry["account_id"], "offer": offer.get("offerId"),
            "payment_intent": intent.get("paymentIntentId"), "itinerary": found.get("itinerary"),
            "quote": quote.get("quoteId"), "service": found.get("service"),
            "place": route.get("origin_place"), "node": found.get("origin_node"),
        }
        ancillary_refs = await self.maybe_purchase_ancillary(order_id, travelers[0], found["segment"])
        common_refs.update(ancillary_refs)
        await self.maybe_read_probe(common_refs)
        if resv.get("no_capacity"):
            return await self.handle_no_capacity_waitlist(
                entry["account_id"], travelers[0], found["segment"], intent["paymentIntentId"], common_refs)
        if self.chance("p_abandon_before_payment"):
            if self.chance("p_cancel_payment_intent_on_abandon"):
                await self.api.request(
                    "POST", "payment", f"/api/v1/payment-intents/{intent['paymentIntentId']}/cancel",
                    {"reason": "CUSTOMER_ABANDONED_CHECKOUT"}, ok=(200,), step="payment-cancel")
                return "cancelled_payment_intent"
            if self.chance("p_cancel_order_before_payment"):
                await self.api.request(
                    "POST", "journey-order", f"/api/v1/journey-orders/{order_id}/cancel",
                    {"reason": "CUSTOMER_CANCELLED_BEFORE_PAYMENT"}, ok=(200,), step="order-cancel-before-payment")
                return "cancelled_before_payment"
            return "abandoned_before_payment"

        await self.api.request("POST", "payment",
                               f"/api/v1/payment-intents/{intent['paymentIntentId']}/capture",
                               {}, ok=(200, 201), step="payment-capture")

        # ticket issuing is a platform/staff action — enqueue & wait
        tick = {"kind": "ticketing", "order": order_id, "sb": sb,
                "traveler": travelers[0], "seg": found["segment"]}
        self.reg.q_ticketing.append(tick)
        ent = await wait_for(tick, "entitlement", self.staff_wait)

        final = await self.poll_order(order_id, {"CONFIRMED"})
        if final != "CONFIRMED":
            raise StepFailed("confirm", f"order {order_id} ended {final}")
        await self.reg.add_purchase(Purchase(
            order=order_id, saga=resv.get("saga", ""), sb=sb, seg=found["segment"],
            traveler=travelers[0], account=entry["account_id"],
            entitlement=ent, total_minor=total_minor, offer=offer.get("offerId", ""),
            payment_intent=intent.get("paymentIntentId", ""), itinerary=found.get("itinerary", ""),
            quote=quote.get("quoteId", "")))
        wallet_refs = await self.maybe_wallet_purchase_benefit(entry["account_id"])
        await self.maybe_read_probe({
            "order": order_id, "account": entry["account_id"], "offer": offer.get("offerId"),
            "payment_intent": intent.get("paymentIntentId"), "entitlement": ent,
            "itinerary": found.get("itinerary"), "quote": quote.get("quoteId"),
            "service": found.get("service"), "place": route.get("origin_place"),
            "node": found.get("origin_node"), **wallet_refs,
        })
        return "purchased"

    async def handle_no_capacity_waitlist(self, account: str, traveler: str, segment: str, payment_intent: str,
                                          refs: dict[str, str | None]) -> str:
        if not self.optional_chance("p_waitlist_on_no_capacity", 0.30):
            return "no_available_capacity"
        minutes = float(self.b.get("waitlist_deadline_minutes", 30))
        deadline = datetime.now(timezone.utc) + timedelta(minutes=minutes)
        intent_fingerprint = f"{traveler}:{segment}"
        itinerary_ref = refs.get("itinerary")
        if not itinerary_ref:
            self.stats.errors["waitlist:missing-itinerary-ref"] += 1
            return "no_available_capacity"
        code, waitlist = await self.api.request(
            "POST", "waitlist", "/api/v1/waitlist-requests",
            {"accountId": account, "travelerRef": traveler, "segmentRef": segment,
             "itineraryRef": itinerary_ref,
             "paymentGuaranteeRef": payment_intent,
             "intentFingerprint": intent_fingerprint,
             "deadline": deadline.strftime("%Y-%m-%dT%H:%M:%SZ")},
            ok=(200, 201, 409), step="waitlist-create")
        if code == 409:
            self.stats.journeys["waitlist:conflict"] += 1
            return "waitlist_conflict"
        waitlist_id = waitlist["waitlistRequestId"]
        status = waitlist.get("status", "QUEUED")
        await self.reg.add_waitlist(WaitlistRef(waitlist_id, account, traveler, segment, payment_intent,
                                                intent_fingerprint, status))
        if status == "QUEUED":
            self.stats.journeys["waitlist:queued"] += 1
        await self.maybe_read_probe({**refs, "waitlist": waitlist_id, "waitlist_traveler": traveler})
        if status in {"QUEUED", "MATCHING", "SUSPENDED"} and self.optional_chance("p_waitlist_cancel", 0.05):
            code, cancelled = await self.api.request(
                "POST", "waitlist", f"/api/v1/waitlist-requests/{quote(waitlist_id)}/cancel",
                {"reason": "CUSTOMER_CHANGED_PLANS"}, ok=(200, 409, 412), step="waitlist-cancel")
            if code == 200 and cancelled.get("status") == "CANCELLED":
                self.stats.journeys["waitlist:cancelled"] += 1
                return "waitlist_cancelled"
        terminal = await self.poll_waitlist(waitlist_id)
        if terminal in {"FULFILLED", "EXPIRED", "CANCELLED"}:
            self.stats.journeys[f"waitlist:{terminal.lower()}"] += 1
        if terminal in {"QUEUED", "MATCHING"}:
            return f"waitlist_{terminal.lower()}"
        return f"waitlist_{terminal.lower()}" if terminal else "waitlist_observed"

    async def poll_waitlist(self, waitlist_id: str) -> str | None:
        status = None
        for _ in range(int(self.cfg.get("waitlist", {}).get("poll_attempts", self.poll_attempts))):
            code, data = await self.api.request("GET", "waitlist",
                                                f"/api/v1/waitlist-requests/{quote(waitlist_id)}",
                                                ok=(), step="waitlist-poll")
            if code == 200:
                status = data.get("status")
                if status in {"FULFILLED", "EXPIRED", "CANCELLED", "CLOSED"}:
                    return status
            await asyncio.sleep(float(self.cfg.get("waitlist", {}).get("poll_interval_seconds", self.poll_interval)))
        return status

    async def poll_order(self, order_id: str, want: set[str], give_up_on_block: bool = False) -> str | None:
        status = None
        for _ in range(self.poll_attempts):
            code, data = await self.api.request("GET", "journey-order",
                                                f"/api/v1/journey-orders/{order_id}", ok=(), step="poll-order")
            if code == 200:
                status = data.get("status")
                if status in want:
                    return status
                if give_up_on_block and status and "BLOCK" in status:
                    return status
            await asyncio.sleep(self.poll_interval)
        return status

    async def _post_sales_case(self, p: Purchase, case_type: str, reason: str) -> str:
        _, case = await self.api.request(
            "POST", "post-sales", "/api/v1/post-sales-cases",
            {"journeyOrderId": p.order, "caseType": case_type,
             "scope": {"orderItemRefs": [p.sb], "segmentRefs": [p.seg],
                        "travelerRefs": [p.traveler], "entitlementRefs": [p.entitlement]},
             "reasonCode": reason, "actorRef": p.account}, step=f"{case_type.lower()}-case")
        case_id = case.get("caseId") or case.get("postSalesCaseId")
        if not case_id:
            raise StepFailed(f"{case_type.lower()}-case", f"no case id in {str(case)[:100]}")
        await self.api.request("POST", "post-sales", f"/api/v1/post-sales-cases/{case_id}/evaluate",
                               {}, ok=(200, 201), step="case-evaluate")
        await self.api.request("POST", "post-sales", f"/api/v1/post-sales-cases/{case_id}/approve",
                               {}, ok=(200, 201), step="case-approve")
        p.post_sales_case = case_id
        await self.maybe_read_probe({"post_sales_case": case_id, "order": p.order})
        return case_id

    async def journey_refund(self) -> str:
        p = await self.reg.take_purchase(self.rng)
        if p is None:
            return "no_purchase_to_refund"
        try:
            case_id = await self._post_sales_case(p, "REFUND", "CUSTOMER_REQUEST")
            if self.chance("p_refund_get_after_completion"):
                refund_id = await self.find_refund_id(case_id)
                if refund_id:
                    await self.assert_get("payment", f"/api/v1/refunds/{refund_id}", "refundId", refund_id, "tail-get-refund")
        except Exception:
            await self.reg.release_purchase(p, "confirmed")
            raise
        await self.reg.release_purchase(p, "refunded")
        return "refunded"

    async def journey_change(self) -> str:
        p = await self.reg.take_purchase(self.rng)
        if p is None:
            return "no_purchase_to_change"
        try:
            await self._post_sales_case(p, "CHANGE", "SCHEDULE_CHANGE")
        except Exception:
            await self.reg.release_purchase(p, "confirmed")
            raise
        await self.reg.release_purchase(p, "changed")
        return "changed"

    async def journey_fulfillment(self) -> str:
        p = await self.reg.take_purchase(self.rng)
        if p is None:
            return "no_purchase_to_fulfill"
        try:
            if self.chance("p_no_show"):
                _, record = await self.api.request(
                    "POST", "fulfillment", "/api/v1/fulfillment-records/no-show",
                    {"entitlementId": p.entitlement, "segmentBookingId": p.sb,
                     "journeyOrderId": p.order, "travelerId": p.traveler,
                     "segmentRef": p.seg, "reason": "BOARDING_WINDOW_EXPIRED"}, step="no-show")
                p.fulfillment_record = record.get("fulfillmentRecordId", "")
                outcome = "no_show"
            else:
                _, record = await self.api.request(
                    "POST", "fulfillment", "/api/v1/fulfillment-records/boarding",
                    {"entitlementId": p.entitlement, "segmentBookingId": p.sb,
                     "journeyOrderId": p.order, "travelerId": p.traveler, "segmentRef": p.seg,
                     "source": "GATE", "sourceEventId": f"gate-{uuid7()}",
                     "occurredAt": now_iso()}, step="boarding")
                p.fulfillment_record = record.get("fulfillmentRecordId", "")
                await self.think()
                await self.api.request(
                    "POST", "fulfillment", "/api/v1/fulfillment-records/completions",
                    {"entitlementId": p.entitlement, "segmentBookingId": p.sb,
                     "journeyOrderId": p.order, "travelerId": p.traveler, "segmentRef": p.seg,
                     "completionSource": "ARRIVAL", "completedAt": now_iso()}, step="completion")
                outcome = "fulfilled"
        except Exception:
            await self.reg.release_purchase(p, "confirmed")
            raise
        await self.reg.release_purchase(p, "fulfilled")
        await self.maybe_read_probe({"fulfillment_record": p.fulfillment_record, "entitlement": p.entitlement, "order": p.order})
        return outcome


    async def journey_ride(self) -> str:
        entry = await self.login_or_register()
        traveler = await self.obtain_traveler(entry)
        window_start = datetime.now(timezone.utc) + timedelta(minutes=2)
        window_end = window_start + timedelta(minutes=30)
        suffix = uuid7()
        body = {
            "pickupRef": f"plc-ride-pick-{suffix}", "dropoffRef": f"plc-ride-drop-{suffix}",
            "timeWindow": {"startAt": window_start.strftime("%Y-%m-%dT%H:%M:%SZ"),
                           "endAt": window_end.strftime("%Y-%m-%dT%H:%M:%SZ")},
            "riderAccountId": entry["account_id"], "travelerRef": traveler,
            "estimatedFareRef": f"fare-est-{suffix}", "intentFingerprint": f"ride:{traveler}:{suffix}",
        }
        _, ride = await self.api.request("POST", "dispatch", "/api/v1/ride-requests", body, step="ride-create")
        ride_id = ride["rideRequestId"]
        branch = weighted_choice(self.rng, self.b.get("ride_branches", {"complete": 0.75, "driver_cancel_reassign": 0.10, "user_cancel": 0.10, "no_show": 0.05}))
        if branch == "user_cancel":
            await self.api.request("POST", "dispatch", f"/api/v1/ride-requests/{quote(ride_id)}/user-cancel",
                                   {"reason": "CUSTOMER_CHANGED_PLANS"}, ok=(200,), step="ride-user-cancel")
            await self.maybe_read_probe({"ride_request": ride_id, "ride_rider": entry["account_id"]})
            return "user_cancelled"
        work = {"kind": "dispatch", "ride": ride_id, "branch": branch}
        self.reg.q_dispatch.append(work)
        outcome = await wait_for(work, "dispatch", self.staff_wait)
        await self.maybe_read_probe({"ride_request": ride_id, "ride_rider": entry["account_id"]})
        return outcome

    async def journey_disruption(self) -> str:
        p = await self.reg.take_purchase(self.rng)
        if p is None:
            return "no_purchase_for_disruption"
        try:
            suffix = uuid7().replace("-", "")[:12]
            _, reported = await self.api.request(
                "POST", "disruption-recovery", "/api/v1/disruptions",
                {"disruptionType": "SERVICE_DELAY", "scheduledServiceRef": f"ssch-lg-{suffix}",
                 "segmentRef": p.seg, "serviceDate": "2026-08-02",
                 "evidence": {"evidenceRef": f"ev-lg-{suffix}", "sourceSystem": "ADMIN", "sourceRecordId": f"lg-{suffix}", "summary": "Loadgen disruption drill"},
                 "affectedOrderIds": [p.order], "reportedBy": {"actorType": "OPERATIONS", "actorId": "loadgen-ops"},
                 "accountId": p.account,
                 "refundScope": {"orderItemRefs": [p.sb], "segmentRefs": [p.seg], "travelerRefs": [p.traveler], "entitlementRefs": [p.entitlement]}},
                ok=(202,), step="disruption-report")
            incident_id = reported.get("incident", {}).get("incidentId")
            case = (reported.get("recoveryCases") or [])[0]
            case_id = case.get("caseId")
            choice = weighted_choice(self.rng, self.b.get("disruption_option_mix", {"WAIT": 0.5, "REFUND": 0.35, "COMPENSATION": 0.15}))
            option_set = case.get("optionSet") or {}
            options = option_set.get("options") or []
            option = next((o for o in options if o.get("optionType") == choice), None) or (options[0] if options else None)
            if option and case.get("status") == "AWAITING_USER_CHOICE":
                _, case = await self.api.request(
                    "POST", "disruption-recovery", f"/api/v1/recovery-cases/{quote(case_id)}/select-option",
                    {"optionId": option["optionId"], "selectedBy": {"actorType": "USER", "actorId": p.account}},
                    ok=(200,), step="disruption-select")
            self.stats.journeys[f"disruption:option:{choice.lower()}"] += 1
            await self.maybe_read_probe({"disruption_incident": incident_id, "disruption_case": case_id})
            return str(case.get("status", "reported")).lower()
        except Exception:
            await self.reg.release_purchase(p, "confirmed")
            raise
        finally:
            if p.status == "consumed":
                await self.reg.release_purchase(p, "confirmed")


    async def journey_transfer(self) -> str:
        suffix = uuid7()
        now = datetime.now(timezone.utc)
        _, rule = await self.api.request(
            "POST", "transfer-management", "/api/v1/mct-rules",
            {"fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION",
             "minimumMinutes": 20, "conditions": {"loadgen": True},
             "validFrom": iso(now - timedelta(days=1))}, ok=(201,), step="transfer-mct-create")
        await self.api.request(
            "POST", "transfer-management", f"/api/v1/mct-rules/{quote(rule['mctRuleId'])}/publish",
            {"publishedBy": {"actorType": "OPERATIONS", "actorId": "loadgen"}, "publishReason": "loadgen"},
            ok=(200,), step="transfer-mct-publish")
        order = f"jo-lg-{suffix}"
        _, plan = await self.api.request(
            "POST", "transfer-management", "/api/v1/transfer-plans",
            {"itineraryRef": f"iti-lg-{suffix}", "planningSnapshotVersion": 1, "journeyOrderId": order,
             "travelerRefs": [f"trav-lg-{suffix}"]}, ok=(201,), step="transfer-plan")
        contract_type = weighted_choice(self.rng, self.b.get("transfer_contract_mix", {"PROTECTED": 0.55, "SELF_TRANSFER": 0.45}))
        _, conn = await self.api.request(
            "POST", "transfer-management", "/api/v1/connections",
            {"transferPlanId": plan["transferPlanId"], "itineraryRef": plan["itineraryRef"], "journeyOrderId": order,
             "previousSegmentRef": f"seg-lg-prev-{suffix}", "nextSegmentRef": f"seg-lg-next-{suffix}",
             "travelerRefs": [f"trav-lg-{suffix}"], "fromNodeRef": "sta-lg-a", "toNodeRef": "sta-lg-a",
             "fromNodeType": "STATION", "toNodeType": "STATION", "transferCategory": "SAME_STATION",
             "contractId": f"cct-lg-{suffix}", "contractType": contract_type,
             "window": {"plannedArrivalAt": iso(now), "nextDepartureAt": iso(now + timedelta(minutes=60)),
                        "nextCutoffAt": iso(now + timedelta(minutes=50))}}, ok=(201,), step="transfer-connection")
        outcome = str(conn.get("status", "planned")).lower()
        if self.rng.random() < float(self.b.get("p_transfer_delay", 0.35)):
            miss = self.rng.random() < float(self.b.get("p_transfer_missed", 0.25))
            eta = now + timedelta(minutes=80 if miss else 40)
            if self.rng.random() < 0.10:
                _, result = await self.api.request(
                    "POST", "transfer-management", "/api/v1/segment-status-reports",
                    {"segmentRef": f"seg-lg-prev-{suffix}", "reportType": "DELAY",
                     "reportedBy": {"actorType": "SYSTEM", "actorId": "loadgen"}, "sourceSystem": "OPERATIONS",
                     "sourceRecordId": f"lg-transfer-{suffix}", "observedAt": now_iso(), "estimatedArrivalAt": iso(eta)},
                    ok=(202,), step="transfer-report-ops")
                updated = (result.get("updatedConnections") or [{}])[0]
                outcome = str(updated.get("status", outcome)).lower()
            else:
                await self.api.request(
                    "POST", "fulfillment", "/api/v1/segment-status",
                    {"segmentRef": f"seg-lg-prev-{suffix}", "scheduledServiceRef": f"svc-lg-{suffix}",
                     "serviceDate": now.date().isoformat(), "status": "DELAY", "sourceSystem": "OPS",
                     "observedAt": now_iso(), "estimatedArrivalAt": iso(eta)},
                    ok=(201,), step="fulfillment-transfer-report")
                outcome = "event_reported"
        await self.maybe_read_probe({"transfer_plan": plan.get("transferPlanId"), "transfer_connection": conn.get("connectionId"), "transfer_journey": order})
        return outcome

    async def journey_support(self) -> str:
        async with self.reg.lock:
            pool = [p for p in self.reg.purchases if p.status != "consumed"]
        if not pool:
            return "no_order_for_support"
        p = self.rng.choice(pool)
        _, case = await self.api.request(
            "POST", "customer-service", "/api/v1/support-cases",
            {"requesterRef": p.traveler, "channel": "APP", "classification": "POST_SALES_HELP",
             "priority": "NORMAL", "description": "Help me with my order",
             "businessReferences": {"journeyOrderId": p.order}}, step="support-case")
        case_id = case.get("supportCaseId") or case.get("caseId")
        if case_id:
            self.reg.q_support.append({"kind": "support", "case": case_id, "requester": p.traveler})
            await self.maybe_read_probe({"support_case": case_id})
        return "support_case"


    # -- long-tail read probes ----------------------------------------------

    def long_tail_enabled(self, key: str = "enabled") -> bool:
        return bool(self.long_tail.get("enabled", True)) and bool(self.long_tail.get(key, True))

    def long_tail_chance(self, key: str, default: float) -> bool:
        if not self.long_tail_enabled():
            return False
        return self.rng.random() < float(self.long_tail.get(key, default))

    async def assert_get(self, service: str, path: str, id_field: str | None, expected: str | None, step: str) -> dict:
        _, data = await self.api.request("GET", service, path, ok=(200,), step=step)
        self.stats.journeys[f"long_tail:{step}"] += 1
        if id_field and expected and data.get(id_field) != expected:
            self.stats.errors[f"long_tail:{step}:id_mismatch"] += 1
            raise StepFailed(step, f"{id_field}={data.get(id_field)} expected {expected}")
        return data

    def assert_list_contains(self, page: dict, id_field: str, expected: str, step: str) -> None:
        items = page.get("items") or []
        if expected not in {item.get(id_field) for item in items if isinstance(item, dict)}:
            self.stats.errors[f"long_tail:{step}:missing_item"] += 1
            raise StepFailed(step, f"{id_field} {expected} not present in list response")

    async def maybe_read_probe(self, refs: dict[str, str | None]) -> None:
        if not self.long_tail_chance("p_read_probe_after_journey", 0.05):
            return
        order_id = refs.get("order")
        account_id = refs.get("account")
        if order_id and account_id:
            page = await self.assert_get("journey-order", f"/api/v1/journey-orders?accountId={quote(account_id)}&limit=20&offset=0", None, None, "tail-list-orders")
            self.assert_list_contains(page, "orderId", order_id, "tail-list-orders")
            await self.assert_get("journey-order", f"/api/v1/journey-orders/{quote(order_id)}", "orderId", order_id, "tail-get-order")
        if refs.get("offer"):
            data = await self.assert_get("offer-management", f"/api/v1/offers/{quote(refs['offer'])}", None, None, "tail-get-offer")
            got = data.get("offerId") or data.get("id")
            if got != refs["offer"]:
                self.stats.errors["long_tail:tail-get-offer:id_mismatch"] += 1
                raise StepFailed("tail-get-offer", f"offer id {got} expected {refs['offer']}")
        if refs.get("ancillary_catalog") and self.long_tail_chance("p_ancillary_read_probe", 0.20):
            await self.assert_get("ancillary-service", f"/api/v1/ancillary-catalog-items/{quote(refs['ancillary_catalog'])}", "catalogItemId", refs["ancillary_catalog"], "tail-get-anc-catalog")
        if refs.get("ancillary_offer") and self.long_tail_chance("p_ancillary_read_probe", 0.20):
            await self.assert_get("ancillary-service", f"/api/v1/ancillary-offers/{quote(refs['ancillary_offer'])}", "ancillaryOfferId", refs["ancillary_offer"], "tail-get-anc-offer")
        if refs.get("ancillary_order_item") and order_id and self.long_tail_chance("p_ancillary_read_probe", 0.20):
            page = await self.assert_get("ancillary-service", f"/api/v1/ancillary-order-items?journeyOrderId={quote(order_id)}&limit=20&offset=0", None, None, "tail-list-anc-items")
            self.assert_list_contains(page, "ancillaryOrderItemId", refs["ancillary_order_item"], "tail-list-anc-items")
            await self.assert_get("ancillary-service", f"/api/v1/ancillary-order-items/{quote(refs['ancillary_order_item'])}", "ancillaryOrderItemId", refs["ancillary_order_item"], "tail-get-anc-item")
        if refs.get("payment_intent"):
            await self.assert_get("payment", f"/api/v1/payment-intents/{quote(refs['payment_intent'])}", "paymentIntentId", refs["payment_intent"], "tail-get-payment-intent")
        if order_id and refs.get("entitlement"):
            page = await self.assert_get("entitlement-ticketing", f"/api/v1/entitlements?journeyOrderId={quote(order_id)}&limit=20&offset=0", None, None, "tail-list-entitlements")
            self.assert_list_contains(page, "entitlementId", refs["entitlement"], "tail-list-entitlements")
            await self.assert_get("entitlement-ticketing", f"/api/v1/entitlements/{quote(refs['entitlement'])}", "entitlementId", refs["entitlement"], "tail-get-entitlement")
        if refs.get("fulfillment_record"):
            await self.assert_get("fulfillment", f"/api/v1/fulfillment-records/{quote(refs['fulfillment_record'])}", "fulfillmentRecordId", refs["fulfillment_record"], "tail-get-fulfillment")
        if refs.get("post_sales_case"):
            await self.assert_get("post-sales", f"/api/v1/post-sales-cases/{quote(refs['post_sales_case'])}", "caseId", refs["post_sales_case"], "tail-get-post-sales")
        if refs.get("place"):
            await self.assert_get("place-network", f"/api/v1/places/{quote(refs['place'])}", "placeId", refs["place"], "tail-get-place")
        if refs.get("node"):
            await self.assert_get("place-network", f"/api/v1/transport-nodes/{quote(refs['node'])}", "nodeId", refs["node"], "tail-get-transport-node")
        if refs.get("service"):
            page = await self.assert_get("service-plan", "/api/v1/scheduled-services?limit=100&offset=0", None, None, "tail-list-scheduled-services")
            try:
                self.assert_list_contains(page, "scheduledServiceRef", refs["service"], "tail-list-scheduled-services")
            except StepFailed:
                # One retry: a service observed via a fresh itinerary may not
                # be visible to the list projection for a beat.
                await asyncio.sleep(2)
                page = await self.assert_get("service-plan", "/api/v1/scheduled-services?limit=100&offset=0", None, None, "tail-list-scheduled-services")
                self.assert_list_contains(page, "scheduledServiceRef", refs["service"], "tail-list-scheduled-services")
            await self.assert_get("service-plan", f"/api/v1/scheduled-services/{quote(refs['service'])}", "scheduledServiceRef", refs["service"], "tail-get-scheduled-service")
        if refs.get("itinerary"):
            await self.assert_get("trip-planning", f"/api/v1/itineraries/{quote(refs['itinerary'])}", "itineraryRef", refs["itinerary"], "tail-get-itinerary")
        if refs.get("support_case"):
            await self.assert_get("customer-service", f"/api/v1/support-cases/{quote(refs['support_case'])}", "caseId", refs["support_case"], "tail-get-support-case")
        if refs.get("quote"):
            await self.assert_get("fare-pricing", f"/api/v1/fare-quotes/{quote(refs['quote'])}", "quoteId", refs["quote"], "tail-get-fare-quote")
        if refs.get("ride_request"):
            await self.assert_get("dispatch", f"/api/v1/ride-requests/{quote(refs['ride_request'])}", "rideRequestId", refs["ride_request"], "tail-get-ride-request")
        if refs.get("ride_rider") and refs.get("ride_request"):
            page = await self.assert_get("dispatch", f"/api/v1/ride-requests?riderAccountId={quote(refs['ride_rider'])}&limit=20&offset=0", None, None, "tail-list-ride-requests")
            self.assert_list_contains(page, "rideRequestId", refs["ride_request"], "tail-list-ride-requests")
        if refs.get("waitlist") and refs.get("waitlist_traveler"):
            page = await self.assert_get("waitlist", f"/api/v1/waitlist-requests?travelerRef={quote(refs['waitlist_traveler'])}&limit=20&offset=0", None, None, "tail-list-waitlist")
            self.assert_list_contains(page, "waitlistRequestId", refs["waitlist"], "tail-list-waitlist")
            await self.assert_get("waitlist", f"/api/v1/waitlist-requests/{quote(refs['waitlist'])}", "waitlistRequestId", refs["waitlist"], "tail-get-waitlist")
        if refs.get("transfer_plan"):
            await self.assert_get("transfer-management", f"/api/v1/transfer-plans/{quote(refs['transfer_plan'])}", "transferPlanId", refs["transfer_plan"], "tail-get-transfer-plan")
        if refs.get("transfer_connection"):
            await self.assert_get("transfer-management", f"/api/v1/connections/{quote(refs['transfer_connection'])}", "connectionId", refs["transfer_connection"], "tail-get-transfer-connection")
        if refs.get("transfer_journey"):
            await self.assert_get("transfer-management", f"/api/v1/connections?journeyOrderId={quote(refs['transfer_journey'])}", None, None, "tail-list-transfer-connections")
        if refs.get("disruption_incident"):
            await self.assert_get("disruption-recovery", f"/api/v1/incidents/{quote(refs['disruption_incident'])}", "incidentId", refs["disruption_incident"], "tail-get-disruption-incident")
        if refs.get("disruption_case"):
            await self.assert_get("disruption-recovery", f"/api/v1/recovery-cases/{quote(refs['disruption_case'])}", "caseId", refs["disruption_case"], "tail-get-disruption-case")
        if refs.get("benefit"):
            await self.assert_get("wallet-promotion", f"/api/v1/benefits/{quote(refs['benefit'])}", "benefitId", refs["benefit"], "tail-get-benefit")
        if refs.get("wallet_account"):
            await self.assert_get("wallet-promotion", f"/api/v1/wallet-accounts/{quote(refs['wallet_account'])}", "accountId", refs["wallet_account"], "tail-get-wallet-account")
            page = await self.assert_get("wallet-promotion", f"/api/v1/benefits?byAccountId={quote(refs['wallet_account'])}&limit=20&offset=0", None, None, "tail-list-benefits")
            if refs.get("benefit"):
                self.assert_list_contains(page, "benefitId", refs["benefit"], "tail-list-benefits")



    async def maybe_purchase_ancillary(self, order_id: str, traveler_ref: str, segment_ref: str) -> dict[str, str]:
        if not self.optional_chance("p_ancillary_purchase", 0.03):
            return {}
        suffix = uuid7()
        now = datetime.now(timezone.utc)
        catalog_body = {
            "serviceType": "MEAL", "displayName": f"Loadgen meal {suffix}", "attachmentScope": "SEGMENT",
            "modalities": ["TRAIN"], "price": {"currency": "CNY", "minorUnits": 1200},
            "salesWindow": {"startAt": iso(now - timedelta(hours=1)), "endAt": iso(now + timedelta(days=1))},
            "purchaseCutoffHoursBeforeDeparture": 1, "eligibilityRuleVersion": "min-v1",
            "requiresEntitlementRef": True, "requiresSegmentRef": True, "fulfillmentMethod": "VOUCHER",
        }
        _, catalog = await self.api.request("POST", "ancillary-service", "/api/v1/ancillary-catalog-items", catalog_body, ok=(201,), step="anc-catalog")
        await self.api.request("POST", "ancillary-service", f"/api/v1/ancillary-catalog-items/{catalog['catalogItemId']}/publish", {"approvalRef": "loadgen", "expectedVersion": catalog.get("version", 1)}, ok=(200,), step="anc-publish")
        _, draft = await self.api.request("POST", "ancillary-service", "/api/v1/ancillary-offers", {"catalogItemId": catalog["catalogItemId"], "journeyOrderId": order_id, "travelerRef": traveler_ref, "segmentRef": segment_ref, "entitlementRef": f"ent-{suffix}", "departureAt": iso(now + timedelta(hours=4)), "quantity": 1}, ok=(201,), step="anc-draft")
        _, offer = await self.api.request("POST", "ancillary-service", f"/api/v1/ancillary-offers/{draft['ancillaryOfferId']}/quote", {"expectedVersion": draft.get("offerVersion", 1), "validitySeconds": 600}, ok=(200,), step="anc-quote")
        _, item = await self.api.request("POST", "ancillary-service", f"/api/v1/ancillary-offers/{offer['ancillaryOfferId']}/select", {"journeyOrderId": order_id, "expectedVersion": offer.get("offerVersion", 2)}, ok=(201,), step="anc-select")
        await self.api.request("POST", "ancillary-service", f"/api/v1/ancillary-order-items/{item['ancillaryOrderItemId']}/confirm", {"reasonCode": "LOADGEN"}, ok=(200,), step="anc-confirm-pending")
        _, confirmed = await self.api.request("POST", "ancillary-service", f"/api/v1/ancillary-order-items/{item['ancillaryOrderItemId']}/confirm", {"confirmationRef": f"anc-conf-{suffix}"}, ok=(200,), step="anc-confirm")
        return {"ancillary_catalog": catalog["catalogItemId"], "ancillary_offer": offer["ancillaryOfferId"], "ancillary_order_item": confirmed["ancillaryOrderItemId"]}

    async def maybe_wallet_purchase_benefit(self, account_id: str) -> dict[str, str]:
        if self.rng.random() >= float(self.wallet_cfg.get("p_purchase_reserve_redeem", 0.02)):
            return {}
        issued = await self.issue_wallet_benefit(account_id, int(self.wallet_cfg.get("purchase_benefit_minor_units", 100)))
        benefit_id = issued["benefitId"]
        ref = f"ord-{uuid7()}"
        amount = issued.get("availableAmount", {}).get("minorUnits", int(self.wallet_cfg.get("purchase_benefit_minor_units", 100)))
        await self.api.request("POST", "wallet-promotion", f"/api/v1/benefits/{quote(benefit_id)}/reserve",
                               {"amount": {"currency": "CNY", "minorUnits": amount}, "reservationRef": ref,
                                "reservationExpiresAt": (datetime.now(timezone.utc) + timedelta(minutes=10)).strftime("%Y-%m-%dT%H:%M:%SZ"),
                                "businessReason": {"reasonType": "ORDER_PURCHASE", "reasonCode": "LOADGEN_BENEFIT_RESERVE", "referenceType": "ORDER", "referenceId": ref}},
                               ok=(200,), step="wallet-reserve")
        await self.api.request("POST", "wallet-promotion", f"/api/v1/benefits/{quote(benefit_id)}/redeem",
                               {"amount": {"currency": "CNY", "minorUnits": amount}, "redemptionRef": ref, "reservationRef": ref,
                                "businessReason": {"reasonType": "ORDER_PURCHASE", "reasonCode": "LOADGEN_BENEFIT_USE", "referenceType": "ORDER", "referenceId": ref}},
                               ok=(200,), step="wallet-redeem")
        self.stats.journeys["wallet:reserve_redeem"] += 1
        return {"benefit": benefit_id, "wallet_account": account_id}

    async def issue_wallet_benefit(self, account_id: str, amount: int) -> dict:
        until = (datetime.now(timezone.utc) + timedelta(days=7)).strftime("%Y-%m-%dT%H:%M:%SZ")
        body = {"accountId": account_id, "benefitType": "BALANCE", "balanceType": "PROMOTION_CREDIT",
                "amount": {"currency": "CNY", "minorUnits": amount}, "issuanceSource": "MANUAL_OPS",
                "applicableScope": {"scopeType": "ANY_TRIP", "currency": "CNY"},
                "redemptionRule": {"singleUse": False, "requiresReservation": False}, "revocationRule": {},
                "validFrom": now_iso(), "validUntil": until,
                "businessReason": {"reasonType": "MANUAL_OPS", "reasonCode": "LOADGEN_MANUAL_OPS", "referenceType": "MANUAL_ACTION", "referenceId": f"act-{uuid7()}"}}
        _, data = await self.api.request("POST", "wallet-promotion", "/api/v1/benefits", body, ok=(201,), step="wallet-issue")
        self.stats.journeys["wallet:issued"] += 1
        return data

    async def find_refund_id(self, case_id: str) -> str | None:
        for _ in range(self.poll_attempts):
            entries = await self.redis.xrevrange("events:payment", count=200)
            for _id, fields in entries:
                raw = fields.get("envelope")
                if not raw:
                    continue
                try:
                    env = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                payload = env.get("payload", {})
                if env.get("eventType") == "RefundRequested" and payload.get("businessCaseRef") == case_id:
                    return payload.get("refundId")
            await asyncio.sleep(self.poll_interval)
        return None

    async def close(self) -> None:
        await self.redis.aclose()

    async def journey_legacy(self) -> str:
        entry = await self.login_or_register()
        tvl = await self.obtain_traveler(entry)
        async with self.reg.lock:
            legacy_routes = [r for r in self.reg.routes if r.get("service_number")]
        if not legacy_routes:
            return "no_legacy_route"
        route = self.rng.choice(legacy_routes)
        headers = {"X-Legacy-Operator": "loadgen-legacy", "X-Legacy-Reason": "LOAD_TEST"}

        async def legacy(op: str, path: str, body: dict) -> dict:
            _, data = await self.api.request("POST", "legacy-acl", path, body,
                                             headers=dict(headers), ok=(200,), step=f"legacy-{op}")
            if data.get("status") != 1:
                raise StepFailed(f"legacy-{op}", f"status={data.get('status')} msg={data.get('msg')}")
            return data.get("data") or {}

        d = await legacy("preserve", "/api/v1/legacy/preserve", {
            "accountId": entry["account_id"], "contactsId": tvl,
            "tripId": route["service_number"],
            "seatType": weighted_choice(self.rng, self.b["seat_types"]),
            "date": route["date"], "from": route["origin_place"], "to": route["dest_place"]})
        order_id = d.get("orderId")
        total = (d.get("total") or {}).get("minorUnits", 10750)
        await self.think()
        await legacy("pay", "/api/v1/legacy/inside_payment",
                     {"orderId": order_id, "price": {"currency": "CNY", "minorUnits": total}})
        await asyncio.sleep(self.poll_interval * 2)
        await legacy("ticket", "/api/v1/legacy/ticket_issue", {"orderId": order_id})
        await self.think()
        if self.chance("p_legacy_cancel"):
            await legacy("cancel", "/api/v1/legacy/cancel", {"orderId": order_id})
            return "legacy_cancelled"
        await legacy("execute", "/api/v1/legacy/execute", {"orderId": order_id})
        return "legacy_completed"



# ---------------------------------------------------------------------------
# low-frequency operations simulator — read-heavy backoffice coverage
# ---------------------------------------------------------------------------


class OpsSim:
    def __init__(self, cfg: dict, api: Api, reg: Registry, stats: Stats, rng: random.Random):
        self.cfg = cfg.get("ops", {})
        self.api = api
        self.reg = reg
        self.stats = stats
        self.rng = rng

    def enabled(self) -> bool:
        return bool(self.cfg.get("enabled", True))

    async def worker(self, stop: asyncio.Event) -> None:
        if not self.enabled():
            return
        interval = self.cfg.get("interval_seconds", {"min": 180, "max": 420})
        while not stop.is_set():
            try:
                await asyncio.wait_for(stop.wait(), timeout=self.rng.uniform(float(interval["min"]), float(interval["max"])))
                continue
            except asyncio.TimeoutError:
                pass
            try:
                await self.sweep_once()
                self.stats.journeys["ops:sweep"] += 1
            except StepFailed as exc:
                self.stats.journeys["ops:failed"] += 1
                self.stats.errors[f"ops:{exc.step}"] += 1
                print(f"[ops] failed — {exc}")
            except Exception as exc:
                self.stats.journeys["ops:crashed"] += 1
                self.stats.errors[f"ops:crashed:{type(exc).__name__}"] += 1
                print(f"[ops] crashed — {type(exc).__name__}: {str(exc)[:180]}")

    async def sweep_once(self) -> None:
        await self.reporting_reads()
        await self.finance_reads()
        await self.wallet_promotion_sweep()
        await self.supplier_catalog_sweep()

    async def reporting_reads(self) -> None:
        await self.api.request("GET", "reporting", "/api/v1/metrics?category=operational&limit=20&offset=0", ok=(200,), step="ops-reporting-metrics")
        self.stats.journeys["ops:reporting:metrics"] += 1
        dashboard = self.cfg.get("dashboard_id", "dash-revenue")
        await self.api.request("GET", "reporting", f"/api/v1/dashboards/{quote(dashboard)}", ok=(200,), step="ops-reporting-dashboard")
        self.stats.journeys["ops:reporting:dashboard"] += 1
        await self.api.request("GET", "reporting", f"/api/v1/dashboards/{quote(dashboard)}/rebuilds?limit=20&offset=0", ok=(200,), step="ops-reporting-rebuilds")
        self.stats.journeys["ops:reporting:rebuilds"] += 1

    async def finance_reads(self) -> None:
        async with self.reg.lock:
            purchases = list(self.reg.purchases)
        if purchases:
            order_id = self.rng.choice(purchases).order
            path = f"/api/v1/reconciliation-cases?orderId={quote(order_id)}&limit=20&offset=0"
        else:
            path = "/api/v1/reconciliation-cases?limit=20&offset=0"
        await self.api.request("GET", "finance-settlement", path, ok=(200,), step="ops-finance-reconciliation-list")
        self.stats.journeys["ops:finance:reconciliation_cases"] += 1

    async def wallet_promotion_sweep(self) -> None:
        if self.rng.random() >= float(self.cfg.get("p_wallet_manual_issue", 0.20)):
            return
        async with self.reg.lock:
            accounts = list(self.reg.accounts)
        if not accounts:
            return
        account_id = self.rng.choice(accounts)["account_id"]
        amount = int(self.cfg.get("wallet_manual_issue_minor_units", 100))
        until = (datetime.now(timezone.utc) + timedelta(days=14)).strftime("%Y-%m-%dT%H:%M:%SZ")
        _, benefit = await self.api.request("POST", "wallet-promotion", "/api/v1/benefits",
            {"accountId": account_id, "benefitType": "BALANCE", "balanceType": "PROMOTION_CREDIT",
             "amount": {"currency": "CNY", "minorUnits": amount}, "issuanceSource": "MANUAL_OPS",
             "applicableScope": {"scopeType": "ANY_TRIP", "currency": "CNY"},
             "redemptionRule": {"singleUse": False, "requiresReservation": False}, "revocationRule": {},
             "validFrom": now_iso(), "validUntil": until,
             "businessReason": {"reasonType": "MANUAL_OPS", "reasonCode": "LOADGEN_MANUAL_OPS", "referenceType": "MANUAL_ACTION", "referenceId": f"act-{uuid7()}"}},
            ok=(201,), step="ops-wallet-issue")
        self.stats.journeys["ops:wallet:issued"] += 1
        await self.api.request("GET", "wallet-promotion", f"/api/v1/benefits/{quote(benefit['benefitId'])}", ok=(200,), step="ops-wallet-get-benefit")
        await self.api.request("GET", "wallet-promotion", f"/api/v1/wallet-accounts/{quote(account_id)}", ok=(200,), step="ops-wallet-get-account")

    async def supplier_catalog_sweep(self) -> None:
        _, suppliers = await self.api.request("GET", "supplier-catalog", "/api/v1/suppliers?limit=20&offset=0", ok=(200,), step="ops-supplier-list")
        self.stats.journeys["ops:supplier:list"] += 1
        for supplier in suppliers.get("items") or []:
            supplier_id = supplier.get("supplierId")
            if supplier_id:
                await self.reg.remember_ops_entity("suppliers", supplier_id)
                break
        async with self.reg.lock:
            known_suppliers = list(self.reg.ops_entities.get("suppliers", []))
        if known_suppliers:
            supplier_id = self.rng.choice(known_suppliers)
            await self.api.request("GET", "supplier-catalog", f"/api/v1/suppliers/{quote(supplier_id)}", ok=(200,), step="ops-supplier-get")
            self.stats.journeys["ops:supplier:get"] += 1
        if self.rng.random() >= float(self.cfg.get("p_supplier_catalog_write", 0.25)):
            return
        suffix = uuid7().split("-")[-1][:8].upper()
        _, supplier = await self.api.request(
            "POST", "supplier-catalog", "/api/v1/suppliers",
            {"legalName": f"Loadgen Rail Supplier {suffix} Ltd", "brandName": f"LG Rail {suffix}",
             "supplierCode": f"LG{suffix}"}, ok=(201,), step="ops-supplier-create")
        supplier_id = supplier["supplierId"]
        await self.reg.remember_ops_entity("suppliers", supplier_id)
        self.stats.journeys["ops:supplier:create"] += 1
        _, carrier = await self.api.request(
            "POST", "supplier-catalog", "/api/v1/carriers",
            {"supplierId": supplier_id, "name": f"Loadgen Carrier {suffix}",
             "code": f"LGC{suffix[:5]}", "transportMode": "RAIL"}, ok=(201,), step="ops-carrier-create")
        carrier_id = carrier["carrierId"]
        await self.reg.remember_ops_entity("carriers", carrier_id)
        self.stats.journeys["ops:carrier:create"] += 1
        _, contract = await self.api.request(
            "POST", "supplier-catalog", "/api/v1/contracts",
            {"supplierId": supplier_id, "carrierId": carrier_id, "contractRef": f"LG-CONTRACT-{suffix}",
             "effectiveFrom": now_iso()}, ok=(201,), step="ops-contract-create")
        contract_id = contract.get("contractId")
        if contract_id:
            await self.reg.remember_ops_entity("contracts", contract_id)
        self.stats.journeys["ops:contract:create"] += 1
        # The API contract defines GET only for suppliers; carrier/contract reads
        # are therefore intentionally represented by persisted IDs plus supplier
        # list/get checks rather than undocumented endpoints.
        if supplier_id:
            await self.api.request("GET", "supplier-catalog", f"/api/v1/suppliers/{quote(supplier_id)}", ok=(200,), step="ops-supplier-get-created")
            self.stats.journeys["ops:supplier:get_created"] += 1

# ---------------------------------------------------------------------------
# bootstrap (ops-side, idempotent) — guarantees searchable inventory
# ---------------------------------------------------------------------------


async def bootstrap(cfg: dict, api: Api, reg: Registry, rng: random.Random) -> None:
    bs = cfg.get("bootstrap") or {}
    if not bs.get("enabled", True):
        return
    _, listing = await api.request("GET", "place-network",
                                   "/api/v1/places?limit=100&offset=0&status=ACTIVE",
                                   ok=(200,), step="list-places")
    by_code = {p.get("code"): p for p in listing.get("items", [])}
    places, nodes = {}, {}
    for city in bs.get("cities", []):
        existing = by_code.get(city["code"])
        if existing:
            places[city["code"]] = existing["placeId"]
        else:
            _, p = await api.request("POST", "place-network", "/api/v1/places",
                                     {"canonicalName": city["name"], "placeType": "CITY",
                                      "code": city["code"], "timezone": "Asia/Shanghai"},
                                     step="create-place")
            places[city["code"]] = p["placeId"]
        _, n = await api.request("POST", "place-network", "/api/v1/transport-nodes",
                                 {"placeId": places[city["code"]],
                                  "displayName": f"{city['name']} Station", "servingModes": ["RAIL"]},
                                 step="create-node")
        nodes[city["code"]] = n.get("nodeId") or n.get("transportNodeId")

    known = {(r["origin_place"], r["dest_place"], r["date"]) for r in reg.routes}
    base = int(bs.get("service_number_base", 5000))
    codes = list(places.keys())
    seq = 0
    for date in bs.get("departure_dates", []):
        for _ in range(int(bs.get("services_per_date", 1))):
            a, b = rng.sample(codes, 2)
            key = (places[a], places[b], date)
            seq += 1
            if key in known:
                continue
            number = f"G{base + len(reg.routes) + seq}"
            dep = f"{date}T{rng.randrange(6, 18):02d}:00:00Z"
            arr = f"{date}T{rng.randrange(19, 23):02d}:30:00Z"
            _, ss = await api.request("POST", "service-plan", "/api/v1/scheduled-services",
                                      {"carrierId": f"car-{uuid7()}", "serviceNumber": number,
                                       "departureTime": dep, "arrivalTime": arr,
                                       "originNodeId": nodes[a], "destinationNodeId": nodes[b]},
                                      step="create-service")
            ss_id = ss.get("scheduledServiceRef") or ss.get("scheduledServiceId")
            await api.request("POST", "service-plan", "/api/v1/service-segments",
                              {"scheduledServiceRef": ss_id, "originStopRef": nodes[a],
                               "destinationStopRef": nodes[b], "departureTime": dep,
                               "arrivalTime": arr}, step="create-segment")
            reg.routes.append({"origin_place": places[a], "dest_place": places[b],
                               "date": date, "service_number": number})
            known.add(key)

    # keep only routes the search API can actually sell (real seg-<uuid>
    # refs); synthetic fallbacks poison downstream ticketing
    probe_tvl = None
    verified = []
    for route in reg.routes:
        ok_route = False
        for _ in range(3):
            try:
                if probe_tvl is None:
                    _, t = await api.request(
                        "POST", "traveler-profile", "/api/v1/travelers",
                        {"accountId": f"acc-{uuid7()}", "travelerType": "ADULT",
                         "givenName": "Boot", "familyName": "Strap"},
                        step="bootstrap-traveler")
                    probe_tvl = t["travelerId"]
                _, res = await api.request(
                    "POST", "trip-planning", "/api/v1/itineraries/search",
                    {"originRef": route["origin_place"], "destinationRef": route["dest_place"],
                     "departureDate": route["date"], "travelerRefs": [probe_tvl],
                     "channel": "WEB"}, ok=(200,), step="bootstrap-verify")
                bookable = next((i for i in res.get("itineraries") or [] if _bookable(i)), None)
                if bookable:
                    leg = bookable["legs"][0]
                    route["scheduled_service"] = leg.get("servicePlanRef")
                    route["origin_node"] = leg.get("originStopRef")
                    route["dest_node"] = leg.get("destinationStopRef")
                    ok_route = True
                    break
            except StepFailed:
                pass
            await asyncio.sleep(5)
        if ok_route:
            verified.append(route)
        else:
            print(f"[bootstrap] dropping unbookable route {route['service_number']} "
                  f"{route['origin_place'][:16]}->{route['dest_place'][:16]} {route['date']}")
    reg.routes[:] = verified

    # discovery sweep: existing place pairs (e.g. the e2e-seeded route) that
    # already sell real segments; capped to a handful of probes per date
    if len(reg.routes) < int(bs.get("min_routes", 2)):
        all_places = [p.get("placeId") for p in listing.get("items", []) if p.get("placeId")]
        seen = {(r["origin_place"], r["dest_place"], r["date"]) for r in reg.routes}
        for date in bs.get("departure_dates", []):
            probes = 0
            for a_place in all_places:
                for b_place in all_places:
                    if a_place == b_place or probes >= 12:
                        continue
                    if (a_place, b_place, date) in seen:
                        continue
                    probes += 1
                    try:
                        _, res = await api.request(
                            "POST", "trip-planning", "/api/v1/itineraries/search",
                            {"originRef": a_place, "destinationRef": b_place,
                             "departureDate": date, "travelerRefs": [probe_tvl],
                             "channel": "WEB"}, ok=(200,), step="bootstrap-discover")
                    except StepFailed:
                        continue
                    bookable = next((i for i in res.get("itineraries") or [] if _bookable(i)), None)
                    if bookable:
                        leg = bookable["legs"][0]
                        reg.routes.append({"origin_place": a_place, "dest_place": b_place,
                                           "date": date, "service_number": None,
                                           "scheduled_service": leg.get("servicePlanRef"),
                                           "origin_node": leg.get("originStopRef"),
                                           "dest_node": leg.get("destinationStopRef")})
                        seen.add((a_place, b_place, date))
    print(f"[bootstrap] routes known (bookable): {len(reg.routes)}")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------


async def customer_worker(idx: int, cfg: dict, sim: CustomerSim, stats: Stats, stop: asyncio.Event) -> None:
    journeys = {
        "browse": sim.journey_browse,
        "purchase": sim.journey_purchase,
        "refund": sim.journey_refund,
        "change": sim.journey_change,
        "fulfillment": sim.journey_fulfillment,
        "support": sim.journey_support,
        "legacy": sim.journey_legacy,
        "ride": sim.journey_ride,
        "disruption": sim.journey_disruption,
        "transfer": sim.journey_transfer,
    }
    pause = cfg["run"]["session_pause_seconds"]
    while not stop.is_set():
        name = weighted_choice(sim.rng, cfg["journey_mix"])
        try:
            outcome = await journeys[name]()
            stats.journeys[f"{name}:{outcome}"] += 1
        except Abandoned:
            stats.journeys[f"{name}:abandoned"] += 1
        except StepFailed as exc:
            stats.journeys[f"{name}:failed"] += 1
            stats.errors[f"journey:{name}:{exc.step}"] += 1
            print(f"[cust{idx}] {name} failed — {exc}")
        except Exception as exc:
            stats.journeys[f"{name}:crashed"] += 1
            print(f"[cust{idx}] {name} crashed — {type(exc).__name__}: {str(exc)[:180]}")
        try:
            await asyncio.wait_for(stop.wait(),
                                   timeout=sim.rng.uniform(float(pause["min"]), float(pause["max"])))
        except asyncio.TimeoutError:
            pass


async def reporter(cfg: dict, stats: Stats, reg: Registry, stop: asyncio.Event) -> None:
    interval = float(cfg["run"]["stats_interval_seconds"])
    state_file = cfg["run"].get("state_file") or ""
    while not stop.is_set():
        try:
            await asyncio.wait_for(stop.wait(), timeout=interval)
        except asyncio.TimeoutError:
            pass
        print("[stats] " + json.dumps(stats.snapshot(), sort_keys=True), flush=True)
        reg.save(state_file)


async def main() -> None:
    cfg_path = os.environ.get("LOADGEN_CONFIG",
                              os.path.join(os.path.dirname(__file__), "config.yaml"))
    cfg = yaml.safe_load(open(cfg_path))
    seed = cfg["run"].get("seed")
    rng = random.Random(seed)
    random.seed(seed)

    stats = Stats()
    api = Api(cfg, stats)
    reg = Registry.load(cfg["run"].get("state_file") or "")
    staff = StaffSim(cfg, api, reg, stats, rng)
    sim = CustomerSim(cfg, api, reg, stats, rng)
    ops = OpsSim(cfg, api, reg, stats, rng)

    try:
        await bootstrap(cfg, api, reg, rng)
    except Exception as exc:
        # inventory guarantees are best-effort; run with whatever routes the
        # registry already knows rather than crash-looping the pod
        print(f"[bootstrap] failed (continuing with {len(reg.routes)} known routes): {exc}")

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    duration = float(cfg["run"].get("duration_seconds") or 0)
    if duration > 0:
        loop.call_later(duration, stop.set)

    tasks = [asyncio.create_task(customer_worker(i, cfg, sim, stats, stop))
             for i in range(int(cfg["run"]["workers"]))]
    tasks += [asyncio.create_task(staff.worker(i, stop))
              for i in range(int(cfg["staff"]["workers"]))]
    if ops.enabled():
        tasks.append(asyncio.create_task(ops.worker(stop)))
    tasks.append(asyncio.create_task(reporter(cfg, stats, reg, stop)))

    await stop.wait()
    await asyncio.gather(*tasks, return_exceptions=True)
    reg.save(cfg["run"].get("state_file") or "")
    print("[final] " + json.dumps(stats.snapshot(), sort_keys=True), flush=True)
    await staff.close()
    await sim.close()
    await api.close()


if __name__ == "__main__":
    asyncio.run(main())
