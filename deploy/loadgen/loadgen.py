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
from typing import Any

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


@dataclass
class Registry:
    accounts: list[dict] = field(default_factory=list)
    purchases: list[Purchase] = field(default_factory=list)
    routes: list[dict] = field(default_factory=list)
    lock: asyncio.Lock = field(default_factory=asyncio.Lock, repr=False)
    # staff work queues (runtime only, never persisted)
    q_reservation: deque = field(default_factory=deque, repr=False)
    q_ticketing: deque = field(default_factory=deque, repr=False)
    q_risk: deque = field(default_factory=deque, repr=False)
    q_support: deque = field(default_factory=deque, repr=False)

    @classmethod
    def load(cls, path: str) -> "Registry":
        reg = cls()
        if path and os.path.exists(path):
            try:
                raw = json.load(open(path))
                reg.accounts = raw.get("accounts", [])
                reg.purchases = [Purchase(**p) for p in raw.get("purchases", [])]
                reg.routes = raw.get("routes", [])
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
                       "routes": self.routes}, fh)
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
            for q in (self.reg.q_reservation, self.reg.q_ticketing, self.reg.q_risk, self.reg.q_support):
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
        item["sb"] = sb

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

    async def do_support(self, item: dict) -> None:
        case_id = item["case"]
        if self.rng.random() < float(self.cfg["p_support_assign"]):
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

    async def think(self) -> None:
        t = self.cfg["run"]["think_time_seconds"]
        await asyncio.sleep(self.rng.uniform(float(t["min"]), float(t["max"])))

    def chance(self, key: str) -> bool:
        return self.rng.random() < float(self.b[key])

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

    async def obtain_traveler(self, entry: dict) -> str:
        if entry["travelers"] and not self.chance("p_new_traveler"):
            tvl = self.rng.choice(entry["travelers"])
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
        itins = data.get("itineraries") or []
        if not itins:
            raise StepFailed("search", f"no itineraries for {route['date']}")
        itin = self.rng.choice(itins)
        return {"itinerary": itin["itineraryRef"], "segment": itin["legs"][0]["serviceSegmentRef"]}

    # -- journeys ----------------------------------------------------------

    async def journey_browse(self) -> str:
        entry = await self.login_or_register()
        tvl = await self.obtain_traveler(entry)
        channel = weighted_choice(self.rng, self.b["channels"])
        found = await self.search(await self.pick_route(), [tvl], channel)
        await self.think()
        if self.chance("p_abandon_after_search"):
            return "browsed"
        await self.api.request("POST", "fare-pricing", "/api/v1/fare-quotes",
                               {"travelerRefs": [tvl], "channel": channel,
                                "segmentRefs": [found["segment"]]}, step="quote")
        return "browsed_with_quote"

    async def journey_purchase(self) -> str:
        entry = await self.login_or_register()
        travelers = [await self.obtain_traveler(entry)]
        if self.chance("p_second_traveler"):
            travelers.append(await self.obtain_traveler(entry))
        channel = weighted_choice(self.rng, self.b["channels"])
        route = await self.pick_route()

        found = await self.search(route, travelers, channel)
        await self.think()
        if self.chance("p_abandon_after_search"):
            raise Abandoned()

        await self.api.request("POST", "fare-pricing", "/api/v1/fare-quotes",
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
        if self.chance("p_abandon_before_payment"):
            return "abandoned_before_payment"

        total_minor = int(offer["total"]["minorUnits"])
        _, intent = await self.api.request(
            "POST", "payment", "/api/v1/payment-intents",
            {"businessRef": order_id, "purpose": "purchase",
             "amount": {"currency": "CNY", "minorUnits": total_minor},
             "payerRef": entry["account_id"]}, step="payment-intent")
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
            entitlement=ent, total_minor=total_minor))
        return "purchased"

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
        return case_id

    async def journey_refund(self) -> str:
        p = await self.reg.take_purchase(self.rng)
        if p is None:
            return "no_purchase_to_refund"
        try:
            await self._post_sales_case(p, "REFUND", "CUSTOMER_REQUEST")
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
                await self.api.request(
                    "POST", "fulfillment", "/api/v1/fulfillment-records/no-show",
                    {"entitlementId": p.entitlement, "segmentBookingId": p.sb,
                     "journeyOrderId": p.order, "travelerId": p.traveler,
                     "segmentRef": p.seg, "reason": "BOARDING_WINDOW_EXPIRED"}, step="no-show")
                outcome = "no_show"
            else:
                await self.api.request(
                    "POST", "fulfillment", "/api/v1/fulfillment-records/boarding",
                    {"entitlementId": p.entitlement, "segmentBookingId": p.sb,
                     "journeyOrderId": p.order, "travelerId": p.traveler, "segmentRef": p.seg,
                     "source": "GATE", "sourceEventId": f"gate-{uuid7()}",
                     "occurredAt": now_iso()}, step="boarding")
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
            self.reg.q_support.append({"kind": "support", "case": case_id})
        return "support_case"

    async def journey_legacy(self) -> str:
        entry = await self.login_or_register()
        tvl = await self.obtain_traveler(entry)
        route = await self.pick_route()
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
    print(f"[bootstrap] routes known: {len(reg.routes)}")


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
    tasks.append(asyncio.create_task(reporter(cfg, stats, reg, stop)))

    await stop.wait()
    await asyncio.gather(*tasks, return_exceptions=True)
    reg.save(cfg["run"].get("state_file") or "")
    print("[final] " + json.dumps(stats.snapshot(), sort_keys=True), flush=True)
    await staff.close()
    await api.close()


if __name__ == "__main__":
    asyncio.run(main())
