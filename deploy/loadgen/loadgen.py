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
import hashlib
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
    """Any itinerary whose first leg carries a real segment ref is bookable."""
    legs = itin.get("legs") or []
    if not legs:
        return False
    ref = str(legs[0].get("serviceSegmentRef", ""))
    return ref.startswith("seg-")


def compute_departure_dates(cfg: dict) -> list[str]:
    """Compute departure dates from config.

    Backward compat: if the legacy ``departure_dates`` key is present it takes
    precedence; otherwise a rolling window is derived from ``departure_window``.
    """
    bs = cfg.get("bootstrap") or {}
    if "departure_dates" in bs:
        return list(bs["departure_dates"])
    window = bs.get("departure_window", {"from_days": 7, "to_days": 21})
    today = datetime.now(timezone.utc).date()
    return [(today + timedelta(days=d)).isoformat()
            for d in range(int(window["from_days"]), int(window["to_days"]) + 1)]


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
        # scalper-specific counters
        self.scalper_attempts: int = 0
        self.scalper_success: int = 0
        self.scalper_blocked: int = 0
        self.scalper_exhausted: int = 0
        self.scalper_ip_rotations: int = 0

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
            "scalper": {
                "attempts": self.scalper_attempts,
                "success": self.scalper_success,
                "blocked": self.scalper_blocked,
                "exhausted": self.scalper_exhausted,
                "ip_rotations": self.scalper_ip_rotations,
            },
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
    journey_date: str = ""


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
    places: dict[str, str] = field(default_factory=dict)
    ops_entities: dict[str, list[str]] = field(default_factory=lambda: {"suppliers": [], "carriers": [], "contracts": []})
    invoice_titles: dict[str, str] = field(default_factory=dict)
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
                reg.places = raw.get("places", {})
                reg.ops_entities = raw.get("ops_entities", reg.ops_entities)
                reg.invoice_titles = raw.get("invoice_titles", {})
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
                       "places": self.places,
                       "ops_entities": self.ops_entities,
                       "invoice_titles": self.invoice_titles}, fh)
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

    async def ensure_invoice_title(self, account_id: str, api: "Api") -> str:
        async with self.lock:
            cached = self.invoice_titles.get(account_id)
        if cached:
            code, data = await api.request("GET", "invoicing", f"/api/v1/invoice-titles/{quote(cached)}", ok=(), step="invoice-title-get")
            if code == 200 and data.get("status") == "ACTIVE":
                return cached
        _, title = await api.request("POST", "invoicing", "/api/v1/invoice-titles",
                                    {"accountId": account_id, "titleType": "PERSONAL", "titleName": "个人", "setAsDefault": True},
                                    ok=(201,), step="invoice-title-create")
        title_id = title["titleId"]
        async with self.lock:
            self.invoice_titles[account_id] = title_id
        return title_id


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
# providers — composable building blocks for journey orchestration
# ---------------------------------------------------------------------------


class Providers:
    """Composable building blocks for journey orchestration.

    Every external dependency — accounts, travelers, identity, trains,
    quotes, offers, orders, payments — is a provider method.  Providers
    compose: ``available_train()`` internally calls ``city_pair()`` +
    ``departure_date()`` + search.  Each provider reads probabilities from
    ``self.ctx``, a dict that is rebuilt every journey iteration by merging
    global ``behavior`` defaults with an optional persona overlay.
    """

    def __init__(self, cfg: dict, api: Api, reg: Registry, stats: Stats,
                 rng: random.Random):
        self.cfg = cfg
        self.api = api
        self.reg = reg
        self.stats = stats
        self.rng = rng
        self._defaults = cfg.get("defaults", {})
        self._global_behavior = dict(cfg.get("behavior", {}))
        self._global_journey_mix = dict(cfg.get("journey_mix", {}))
        self._personas = cfg.get("personas")
        self.ctx: dict[str, Any] = dict(self._global_behavior)
        self.journey_mix: dict[str, float] = dict(self._global_journey_mix)

    # --- persona overlay ---------------------------------------------------

    def pick_persona(self) -> dict | None:
        """Select a persona based on configured weights (or None)."""
        if not self._personas:
            return None
        weights = {n: float(p.get("weight", 1))
                   for n, p in self._personas.items()}
        chosen = weighted_choice(self.rng, weights)
        return self._personas[chosen]

    def apply_persona(self, persona: dict | None) -> None:
        """Merge persona overrides on top of global behavior."""
        self.ctx = dict(self._global_behavior)
        self.journey_mix = dict(self._global_journey_mix)
        if persona:
            self.ctx.update(persona.get("overrides", {}))
            jw = persona.get("journey_weights")
            if jw:
                self.journey_mix = dict(jw)

    # --- config accessors --------------------------------------------------

    def currency(self) -> str:
        return str(self._defaults.get("currency", "CNY"))

    def default_amount(self, key: str, fallback: int) -> int:
        return int(self._defaults.get(key, fallback))

    # --- atomic providers --------------------------------------------------

    async def account(self) -> dict:
        """Provide an account: create new or reuse existing."""
        p_new = float(self.ctx.get("p_new_account", 0.35))
        if self.rng.random() >= p_new:
            entry = await self.reg.pick_account(self.rng)
            if entry is not None:
                code, _ = await self.api.request(
                    "GET", "account",
                    f"/api/v1/accounts/{entry['account_id']}",
                    ok=(), step="login")
                if code == 200:
                    return entry
        account_id = f"acc-{uuid7()}"
        await self.api.request("POST", "account", "/api/v1/accounts",
                               {"accountId": account_id}, ok=(200, 201),
                               step="register-account")
        return await self.reg.add_account(account_id)

    async def traveler(self, entry: dict, exclude: tuple = ()) -> str:
        """Provide a traveler: create new or reuse existing."""
        p_new = float(self.ctx.get("p_new_traveler", 0.50))
        pool = [t for t in entry["travelers"] if t not in exclude]
        if pool and self.rng.random() >= p_new:
            tvl = self.rng.choice(pool)
            code, _ = await self.api.request(
                "GET", "traveler-profile", f"/api/v1/travelers/{tvl}",
                ok=(), step="get-traveler")
            if code == 200:
                return tvl
        given, family = rand_name(self.rng)
        traveler_types = self.ctx.get(
            "traveler_types",
            {"ADULT": 0.85, "CHILD": 0.10, "SENIOR": 0.05})
        _, data = await self.api.request(
            "POST", "traveler-profile", "/api/v1/travelers",
            {"accountId": entry["account_id"],
             "travelerType": weighted_choice(self.rng, traveler_types),
             "givenName": given, "familyName": family},
            step="create-traveler")
        tvl = data["travelerId"]
        entry["travelers"].append(tvl)
        if len(entry["travelers"]) > 20:
            entry["travelers"].pop(0)
        return tvl

    async def identity(self, traveler_id: str) -> dict[str, str]:
        """Ensure traveler has a verified identity credential."""
        tail = str(self.rng.randint(0, 5))
        doc = f"loadgen-{traveler_id}-{tail}"
        document_hash = hashlib.sha256(doc.encode()).hexdigest() + tail
        name_hash = hashlib.sha256(
            ("name-" + traveler_id).encode()).hexdigest()
        valid_until = (datetime.now(timezone.utc)
                       + timedelta(days=365)).replace(microsecond=0)
        credential_body = {
            "travelerId": traveler_id,
            "profileSnapshotVersion": "loadgen-v1",
            "documentType": "ID_CARD",
            "maskedDocumentNo": f"LG***********{tail}",
            "documentHash": document_hash,
            "canonicalNameHash": name_hash,
            "validUntil": iso(valid_until),
        }
        _, credential = await self.api.request(
            "POST", "identity-verification",
            "/api/v1/identity-verification/credentials",
            credential_body, ok=(200, 201), step="identity-credential")
        material = "|".join([name_hash, "ID_CARD", document_hash, "",
                             iso(valid_until), "", "loadgen-v1"])
        verify_body = {
            "travelerId": traveler_id,
            "credentialRecordId": credential["credentialRecordId"],
            "purpose": "ORDER_CREATION",
            "materialFingerprint": hashlib.sha256(
                material.encode()).hexdigest(),
            "simPolicyVersion": "sim-tail-v1",
            "requestedAt": now_iso(),
        }
        _, case = await self.api.request(
            "POST", "identity-verification",
            "/api/v1/identity-verification/verification-cases",
            verify_body, ok=(200, 201), step="identity-verify")
        return {"identity_credential": credential["credentialRecordId"],
                "identity_case": case["verificationCaseId"]}

    # --- discovery providers -----------------------------------------------

    def city_pair(self) -> tuple[str, str, str, str]:
        """Pick a random origin/destination pair from known places.

        Returns ``(origin_code, dest_code, origin_place_id, dest_place_id)``.
        """
        places = self.reg.places
        if len(places) < 2:
            raise StepFailed("city-pair",
                             "fewer than 2 places in registry")
        codes = list(places.keys())
        origin, dest = self.rng.sample(codes, 2)
        return origin, dest, places[origin], places[dest]

    def departure_date(self) -> str:
        """Pick a date in the booking window (config-driven)."""
        bs = self.cfg.get("bootstrap", {})
        if "departure_dates" in bs:
            return self.rng.choice(bs["departure_dates"])
        window = bs.get("departure_window",
                        {"from_days": 7, "to_days": 21})
        from_days = int(window.get("from_days", 7))
        to_days = int(window.get("to_days", 21))
        today = datetime.now(timezone.utc).date()
        return (today + timedelta(
            days=self.rng.randint(from_days, to_days))).isoformat()

    async def available_train(self, travelers: list[str],
                              channel: str) -> dict:
        """Discovery-based: pick city pair + date, search for trains.

        Falls back to known routes if fewer than 2 places are registered.
        """
        if len(self.reg.places) >= 2:
            o_code, d_code, o_place, d_place = self.city_pair()
            date = self.departure_date()
            _, data = await self.api.request(
                "POST", "trip-planning", "/api/v1/itineraries/search",
                {"originRef": o_place, "destinationRef": d_place,
                 "departureDate": date, "travelerRefs": travelers,
                 "channel": channel},
                ok=(200,), step="search")
            itins = [i for i in (data.get("itineraries") or [])
                     if _bookable(i)]
            if itins:
                itin = self.rng.choice(itins)
                leg = itin["legs"][0]
                return {
                    "itinerary": itin["itineraryRef"],
                    "segment": leg["serviceSegmentRef"],
                    "service": leg.get("servicePlanRef"),
                    "origin_node": leg.get("originStopRef"),
                    "dest_node": leg.get("destinationStopRef"),
                    "date": date,
                    "origin_place": o_place,
                    "dest_place": d_place,
                }
        # fallback to known routes
        async with self.reg.lock:
            routes = list(self.reg.routes)
        if not routes:
            raise StepFailed("available-train",
                             "no routes and fewer than 2 places")
        route = self.rng.choice(routes)
        _, data = await self.api.request(
            "POST", "trip-planning", "/api/v1/itineraries/search",
            {"originRef": route["origin_place"],
             "destinationRef": route["dest_place"],
             "departureDate": route["date"],
             "travelerRefs": travelers, "channel": channel},
            ok=(200,), step="search")
        itins = [i for i in (data.get("itineraries") or [])
                 if _bookable(i)]
        if not itins:
            raise StepFailed(
                "search",
                f"no bookable itinerary for {route['date']}")
        itin = self.rng.choice(itins)
        leg = itin["legs"][0]
        return {
            "itinerary": itin["itineraryRef"],
            "segment": leg["serviceSegmentRef"],
            "service": (leg.get("servicePlanRef")
                        or route.get("scheduled_service")),
            "origin_node": (leg.get("originStopRef")
                            or route.get("origin_node")),
            "dest_node": (leg.get("destinationStopRef")
                          or route.get("dest_node")),
            "date": route["date"],
            "origin_place": route.get("origin_place", ""),
            "dest_place": route.get("dest_place", ""),
        }

    # --- transactional providers -------------------------------------------

    async def fare_quote(self, travelers: list[str], channel: str,
                         segments: list[str]) -> dict:
        """Get a fare quote for the given segments."""
        _, q = await self.api.request(
            "POST", "fare-pricing", "/api/v1/fare-quotes",
            {"travelerRefs": travelers, "channel": channel,
             "segmentRefs": segments}, step="quote")
        return q

    async def offer(self, account_id: str, channel: str,
                    itinerary: str, travelers: list[str]) -> dict:
        """Create an offer (retries on 422 — quote event propagation)."""
        for attempt in range(5):
            try:
                _, o = await self.api.request(
                    "POST", "offer-management", "/api/v1/offers",
                    {"accountId": account_id, "channelId": channel,
                     "itineraryRef": itinerary,
                     "travelerRefs": travelers},
                    step="offer")
                return o
            except StepFailed as exc:
                if "422" not in str(exc) or attempt == 4:
                    raise
                await asyncio.sleep(min(1.0 * (1.5 ** attempt), 4.0))
        raise StepFailed("offer", "exhausted retries")

    async def order(self, account_id: str, offer_data: dict,
                    travelers: list[str], segments: list[str],
                    date: str) -> dict:
        """Create a journey order."""
        _, o = await self.api.request(
            "POST", "journey-order", "/api/v1/journey-orders",
            {"accountId": account_id,
             "offerId": offer_data["offerId"],
             "offerVersion": offer_data.get("offerVersion", 1),
             "travelerRefs": travelers, "segmentRefs": segments,
             "journeyDate": date, "productCode": "TRAIN"},
            step="order")
        return o

    async def payment_intent(self, order_id: str, amount_minor: int,
                             payer: str) -> dict:
        """Create a payment intent."""
        _, intent = await self.api.request(
            "POST", "payment", "/api/v1/payment-intents",
            {"businessRef": order_id, "purpose": "purchase",
             "amount": {"currency": self.currency(),
                        "minorUnits": amount_minor},
             "payerRef": payer}, step="payment-intent")
        return intent

    async def payment_capture(self, intent_id: str,
                              fault_seed: str | None = None) -> None:
        """Capture a payment intent."""
        channel_ref: dict[str, str] = {"channel": "ALIPAY_SIM"}
        if fault_seed:
            channel_ref["faultSeedRef"] = fault_seed
        await self.api.request(
            "POST", "payment",
            f"/api/v1/payment-intents/{intent_id}/capture",
            {"channelRef": channel_ref}, ok=(200, 201, 202),
            step="payment-capture")


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
        body = {"segmentBookingId": item["sb"], "journeyOrderId": item["order"],
                "travelerRef": item["traveler"], "segmentRef": item["seg"], "issuePurpose": "INITIAL"}
        if self.rng.random() < float(self.cfg.get("p_seat_preferences", 0.03)):
            body["seatPreferences"] = {"acceptStanding": True, "adjacencyPreference": "NONE",
                                       "preferenceVersion": "loadgen-v1"}
        _, data = await self.api.request(
            "POST", "entitlement-ticketing", "/api/v1/entitlements", body,
            step="staff-ticketing")
        item["entitlement"] = data["entitlementId"]
        if data.get("seatRef"):
            item["seatAllocationId"] = data["seatRef"].get("seatAllocationId")

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
    def __init__(self, cfg: dict, api: Api, reg: Registry, stats: Stats,
                 rng: random.Random, prov: Providers):
        self.cfg = cfg
        self.api = api
        self.reg = reg
        self.stats = stats
        self.rng = rng
        self.prov = prov
        self.poll_attempts = int(cfg["polling"]["attempts"])
        self.poll_interval = float(cfg["polling"]["interval_seconds"])
        self.staff_wait = float(cfg["behavior"].get("staff_wait_seconds", 90))
        self.long_tail = cfg.get("long_tail", {})
        self.wallet_cfg = cfg.get("wallet_promotion", {})
        self.redis = aioredis.from_url(cfg["target"]["redis_url"], decode_responses=True)

    @property
    def b(self) -> dict:
        """Active behavior context — global defaults merged with persona."""
        return self.prov.ctx

    async def think(self) -> None:
        t = self.prov.ctx.get("think_time",
                              self.cfg["run"]["think_time_seconds"])
        await asyncio.sleep(self.rng.uniform(float(t["min"]), float(t["max"])))

    def chance(self, key: str) -> bool:
        return self.rng.random() < float(self.prov.ctx[key])

    def optional_chance(self, key: str, default: float = 0.0) -> bool:
        return self.rng.random() < float(self.prov.ctx.get(key, default))

    # -- delegators to Providers (unchanged call-sites get new behavior) -----

    async def login_or_register(self) -> dict:
        return await self.prov.account()

    async def ensure_identity_verified(self, traveler_id: str) -> dict[str, str]:
        return await self.prov.identity(traveler_id)

    async def obtain_traveler(self, entry: dict, exclude: tuple = ()) -> str:
        return await self.prov.traveler(entry, exclude)

    async def discover_and_search(self, travelers: list[str],
                                  channel: str) -> dict:
        """Discovery-based search — delegates to providers."""
        return await self.prov.available_train(travelers, channel)

    # -- journeys ----------------------------------------------------------

    async def journey_browse(self) -> str:
        entry = await self.login_or_register()
        tvl = await self.obtain_traveler(entry)
        channel = weighted_choice(self.rng, self.b["channels"])
        found = await self.discover_and_search([tvl], channel)
        await self.think()
        if self.chance("p_abandon_after_search"):
            return "browsed"
        quote = await self.prov.fare_quote([tvl], channel, [found["segment"]])
        await self.maybe_read_probe({"quote": quote.get("quoteId"), "itinerary": found.get("itinerary")})
        return "browsed_with_quote"

    async def journey_purchase(self) -> str:
        entry = await self.login_or_register()
        travelers = [await self.obtain_traveler(entry)]
        if self.chance("p_second_traveler"):
            travelers.append(await self.obtain_traveler(entry, exclude=tuple(travelers)))
        identity_refs = {}
        async with self.reg.lock:
            verified = entry.setdefault("identity_verified", [])
        for traveler in travelers:
            if traveler not in verified:
                identity_refs.update(await self.ensure_identity_verified(traveler))
                async with self.reg.lock:
                    if traveler not in entry.setdefault("identity_verified", []):
                        entry["identity_verified"].append(traveler)
                verified = entry.get("identity_verified", [])
        channel = weighted_choice(self.rng, self.b["channels"])

        found = await self.discover_and_search(travelers, channel)
        await self.think()
        if self.chance("p_abandon_after_search"):
            raise Abandoned()

        quote = await self.prov.fare_quote(travelers, channel, [found["segment"]])
        await self.think()
        if self.chance("p_abandon_after_quote"):
            raise Abandoned()

        offer = await self.prov.offer(entry["account_id"], channel,
                                      found["itinerary"], travelers)
        order = await self.prov.order(entry["account_id"], offer, travelers,
                                      [found["segment"]], found["date"])
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
        intent = await self.prov.payment_intent(order_id, total_minor,
                                                entry["account_id"])
        common_refs = {
            "order": order_id, "account": entry["account_id"],
            "offer": offer.get("offerId"),
            "payment_intent": intent.get("paymentIntentId"),
            "itinerary": found.get("itinerary"),
            "quote": quote.get("quoteId"), "service": found.get("service"),
            "place": found.get("origin_place"),
            "node": found.get("origin_node"),
        }
        ancillary_refs = await self.maybe_purchase_ancillary(order_id, travelers[0], found["segment"])
        common_refs.update(ancillary_refs)
        await self.maybe_read_probe(common_refs)
        if resv.get("no_capacity"):
            return await self.handle_no_capacity_waitlist(
                entry["account_id"], travelers[0], found["segment"],
                intent["paymentIntentId"], common_refs)
        if self.chance("p_abandon_before_payment"):
            if self.chance("p_cancel_payment_intent_on_abandon"):
                await self.api.request(
                    "POST", "payment",
                    f"/api/v1/payment-intents/{intent['paymentIntentId']}/cancel",
                    {"reason": "CUSTOMER_ABANDONED_CHECKOUT"}, ok=(200,),
                    step="payment-cancel")
                return "cancelled_payment_intent"
            if self.chance("p_cancel_order_before_payment"):
                await self.api.request(
                    "POST", "journey-order",
                    f"/api/v1/journey-orders/{order_id}/cancel",
                    {"reason": "CUSTOMER_CANCELLED_BEFORE_PAYMENT"},
                    ok=(200,), step="order-cancel-before-payment")
                return "cancelled_before_payment"
            return "abandoned_before_payment"

        fault_seed = ("MISSED_ORDER:loadgen"
                      if self.chance("p_payment_channel_missed_seed")
                      else None)
        await self.prov.payment_capture(intent["paymentIntentId"],
                                        fault_seed)

        # ticket issuing is a platform/staff action — enqueue & wait
        tick = {"kind": "ticketing", "order": order_id, "sb": sb,
                "traveler": travelers[0], "seg": found["segment"]}
        self.reg.q_ticketing.append(tick)
        ent = await wait_for(tick, "entitlement", self.staff_wait)

        final = await self.poll_order(order_id, {"CONFIRMED"})
        if final != "CONFIRMED":
            raise StepFailed("confirm", f"order {order_id} ended {final}")
        purchase = Purchase(
            order=order_id, saga=resv.get("saga", ""), sb=sb,
            seg=found["segment"], traveler=travelers[0],
            account=entry["account_id"], entitlement=ent,
            total_minor=total_minor, offer=offer.get("offerId", ""),
            payment_intent=intent.get("paymentIntentId", ""),
            itinerary=found.get("itinerary", ""),
            quote=quote.get("quoteId", ""),
            journey_date=found.get("date", ""))
        await self.reg.add_purchase(purchase)
        invoice_refs = await self.maybe_request_invoice(purchase)
        wallet_refs = await self.maybe_wallet_purchase_benefit(entry["account_id"])
        await self.maybe_read_probe({
            "order": order_id, "account": entry["account_id"],
            "offer": offer.get("offerId"),
            "payment_intent": intent.get("paymentIntentId"),
            "entitlement": ent, "itinerary": found.get("itinerary"),
            "quote": quote.get("quoteId"),
            "service": found.get("service"),
            "place": found.get("origin_place"),
            "node": found.get("origin_node"),
            **invoice_refs, **wallet_refs,
        })
        return "purchased"


    async def maybe_request_invoice(self, p: Purchase) -> dict[str, str]:
        if not self.optional_chance("p_invoice_after_purchase", 0.02):
            return {}
        title_id = await self.reg.ensure_invoice_title(p.account, self.api)
        currency = self.prov.currency()
        basis = {
            "basisType": "REVENUE_RECOGNITION",
            "revenueRecognitionIds": [f"rr-loadgen-{p.order[-8:]}"],
            "taxLines": [{"taxCode": "VAT_SIM", "taxRateBasisPoints": 0,
                           "taxableAmount": {"currency": currency, "minorUnits": p.total_minor},
                           "taxAmount": {"currency": currency, "minorUnits": 0}}],
            "totalAmount": {"currency": currency, "minorUnits": p.total_minor},
        }
        basis["amountBasisHash"] = "sha256:" + hashlib.sha256(json.dumps(basis, sort_keys=True).encode()).hexdigest()
        code, req = await self.api.request("POST", "invoicing", "/api/v1/e-invoice-requests",
            {"accountId": p.account, "orderId": p.order, "titleId": title_id, "titleVersion": 1,
             "invoiceScope": {"scopeType": "ORDER"}, "amountBasis": basis,
             "recipientEmail": "loadgen@example.com", "simSeedRef": "loadgen-accept"},
            ok=(201, 409, 412, 422), step="invoice-request")
        if code == 201:
            self.stats.journeys[f"invoicing:{str(req.get('status','unknown')).lower()}"] += 1
            return {"invoice_title": title_id, "invoice_request": req.get("invoiceRequestId"), "invoice": req.get("eInvoiceId")}
        self.stats.journeys[f"invoicing:skipped:{code}"] += 1
        return {"invoice_title": title_id}

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
                 "segmentRef": p.seg, "serviceDate": p.journey_date or self.prov.departure_date(),
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


    # -- new-service journeys ------------------------------------------------

    async def journey_loyalty(self) -> str:
        """Enroll (idempotent) then read membership tier."""
        entry = await self.login_or_register()
        aid = entry["account_id"]
        code, member = await self.api.request(
            "POST", "loyalty-membership", "/members/enroll",
            {"accountId": aid},
            ok=(200, 201), step="loyalty-enroll")
        member_id = member.get("memberId") if isinstance(member, dict) else None
        if not member_id:
            return "loyalty_enroll_failed"
        _, details = await self.api.request(
            "GET", "loyalty-membership", f"/members/{member_id}",
            ok=(200, 404), step="loyalty-get-member")
        if details and isinstance(details, dict) and details.get("tier"):
            return "loyalty_checked"
        return "loyalty_enrolled"

    async def journey_insurance(self) -> str:
        """Issue a travel insurance policy with full required fields."""
        entry = await self.login_or_register()
        tvl = await self.obtain_traveler(entry)
        now_dt = datetime.now(timezone.utc)
        premium = self.prov.default_amount("insurance_premium_minor", 50000)
        currency = self.prov.currency()
        _, policy = await self.api.request(
            "POST", "travel-insurance", "/api/v1/policies",
            {"accountId": entry["account_id"],
             "travelerRef": tvl,
             "productCode": "DELAY_INSURANCE",
             "productVersion": "v1",
             "journeyOrderId": f"ord-{uuid7()}",
             "ancillaryOrderItemId": f"anc-{uuid7()[:8]}",
             "segmentRefs": [f"seg-ins-{uuid7()[:8]}"],
             "paymentIntentId": f"pi-{uuid7()[:8]}",
             "premium": {"currency": currency, "minorUnits": premium},
             "coverageStartAt": now_dt.isoformat(),
             "coverageEndAt": (now_dt + timedelta(days=1)).isoformat()},
            ok=(200, 201), step="insurance-issue-policy")
        if isinstance(policy, dict) and policy.get("policyId"):
            return "insurance_policy_created"
        return "insurance_policy_failed"

    async def journey_group_booking(self) -> str:
        """Create a group booking for 10+ travelers."""
        entry = await self.login_or_register()
        currency = self.prov.currency()
        fare_minor = self.prov.default_amount("group_fare_minor", 85000)
        discount_bp = self.prov.default_amount("group_discount_basis_points", 500)
        _, group = await self.api.request(
            "POST", "group-booking", "/api/v1/group-bookings",
            {"organizerRef": entry["account_id"],
             "segmentRefs": [f"seg-group-{uuid7()[:8]}"],
             "targetTravelerCount": 10,
             "fare": {"currency": currency, "minorUnits": fare_minor,
                      "discountBasisPoints": discount_bp,
                      "negotiationRef": f"nego-{uuid7()[:8]}"}},
            ok=(200, 201), step="group-create")
        group_id = group.get("groupBookingId") or group.get("id")
        if group_id:
            return "group_created"
        return "group_failed"

    async def journey_corporate(self) -> str:
        """Create a corporate travel agreement with full schema."""
        entry = await self.login_or_register()
        now_dt = datetime.now(timezone.utc)
        _, agreement = await self.api.request(
            "POST", "corporate-travel", "/api/v1/agreements",
            {"corporateId": f"corp-{uuid7()[:8]}",
             "agreementCode": f"AGR-{uuid7()[:8]}",
             "legalName": f"Corp-{uuid7()[:8]} Ltd.",
             "effectiveWindow": {
                 "startsAt": now_dt.isoformat(),
                 "endsAt": (now_dt + timedelta(days=365)).isoformat()},
             "priceRef": {
                 "fareRuleRefs": [],
                 "ruleSetId": f"rs-{uuid7()[:8]}",
                 "ruleSetVersion": "v1"},
             "monthlyCreditLimit": {"currency": self.prov.currency(),
                                    "minorUnits": self.prov.default_amount("corporate_credit_limit_minor", 5000000)},
             "billingCalendar": {
                 "billingPeriod": "MONTHLY",
                 "cutoffAt": (now_dt + timedelta(days=30)).isoformat(),
                 "dueAt": (now_dt + timedelta(days=45)).isoformat()},
             "contact": {"email": "corp@example.com", "phone": "+86-10-12345678"},
             "activate": True},
            ok=(200, 201), step="corporate-create")
        if isinstance(agreement, dict) and agreement.get("agreementId"):
            return "corporate_agreement_created"
        return "corporate_agreement_failed"

    async def journey_campaign(self) -> str:
        """Draft a marketing campaign with required externalKey."""
        ext_key = f"camp-{uuid7()[:8]}"
        now_dt = datetime.now(timezone.utc)
        window_end = (now_dt + timedelta(days=30)).isoformat()
        _, campaign = await self.api.request(
            "POST", "marketing-campaign", "/api/v1/campaigns",
            {"externalKey": ext_key,
             "name": f"Campaign-{uuid7()[:8]}",
             "window": {"validFrom": now_dt.isoformat(), "validUntil": window_end}},
            ok=(200, 201), step="campaign-draft")
        campaign_id = campaign.get("campaignId") or campaign.get("id") if isinstance(campaign, dict) else None
        if campaign_id:
            return "campaign_drafted"
        return "campaign_draft_failed"

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
        if self.long_tail_chance("p_payment_channel_read_probe", 0.10):
            await self.assert_get("payment-channel", "/api/v1/channel-statements?channel=ALIPAY_SIM&limit=5&offset=0", None, None, "tail-list-channel-statements")
        if refs.get("invoice_title"):
            await self.assert_get("invoicing", f"/api/v1/invoice-titles/{quote(refs['invoice_title'])}", "titleId", refs["invoice_title"], "tail-get-invoice-title")
        if refs.get("invoice_request"):
            await self.assert_get("invoicing", f"/api/v1/e-invoice-requests/{quote(refs['invoice_request'])}", "invoiceRequestId", refs["invoice_request"], "tail-get-invoice-request")
        if refs.get("invoice"):
            await self.assert_get("invoicing", f"/api/v1/e-invoices/{quote(refs['invoice'])}", "eInvoiceId", refs["invoice"], "tail-get-e-invoice")
        if refs.get("order") and refs.get("invoice"):
            page = await self.assert_get("invoicing", f"/api/v1/e-invoices?orderId={quote(refs['order'])}&limit=20&offset=0", None, None, "tail-list-e-invoices")
            self.assert_list_contains(page, "eInvoiceId", refs["invoice"], "tail-list-e-invoices")
        if order_id and refs.get("entitlement"):
            page = await self.assert_get("entitlement-ticketing", f"/api/v1/entitlements?journeyOrderId={quote(order_id)}&limit=20&offset=0", None, None, "tail-list-entitlements")
            self.assert_list_contains(page, "entitlementId", refs["entitlement"], "tail-list-entitlements")
            ent = await self.assert_get("entitlement-ticketing", f"/api/v1/entitlements/{quote(refs['entitlement'])}", "entitlementId", refs["entitlement"], "tail-get-entitlement")
            seat_ref = ent.get("seatRef") if isinstance(ent, dict) else None
            if seat_ref and seat_ref.get("seatAllocationId"):
                await self.assert_get("seat-assignment", f"/api/v1/seat-allocations/{quote(seat_ref['seatAllocationId'])}", "seatAllocationId", seat_ref["seatAllocationId"], "tail-get-seat-allocation")
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
        if refs.get("identity_credential"):
            await self.assert_get("identity-verification", f"/api/v1/identity-verification/credentials/{quote(refs['identity_credential'])}/verification-status", "credentialRecordId", refs["identity_credential"], "tail-get-identity-credential")
        if refs.get("identity_case"):
            await self.assert_get("identity-verification", f"/api/v1/identity-verification/verification-cases/{quote(refs['identity_case'])}", "verificationCaseId", refs["identity_case"], "tail-get-identity-case")
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
            "modalities": ["TRAIN"], "price": {"currency": self.prov.currency(), "minorUnits": 1200},
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
        currency = self.prov.currency()
        await self.api.request("POST", "wallet-promotion", f"/api/v1/benefits/{quote(benefit_id)}/reserve",
                               {"amount": {"currency": currency, "minorUnits": amount}, "reservationRef": ref,
                                "reservationExpiresAt": (datetime.now(timezone.utc) + timedelta(minutes=10)).strftime("%Y-%m-%dT%H:%M:%SZ"),
                                "businessReason": {"reasonType": "ORDER_PURCHASE", "reasonCode": "LOADGEN_BENEFIT_RESERVE", "referenceType": "ORDER", "referenceId": ref}},
                               ok=(200,), step="wallet-reserve")
        await self.api.request("POST", "wallet-promotion", f"/api/v1/benefits/{quote(benefit_id)}/redeem",
                               {"amount": {"currency": currency, "minorUnits": amount}, "redemptionRef": ref, "reservationRef": ref,
                                "businessReason": {"reasonType": "ORDER_PURCHASE", "reasonCode": "LOADGEN_BENEFIT_USE", "referenceType": "ORDER", "referenceId": ref}},
                               ok=(200,), step="wallet-redeem")
        self.stats.journeys["wallet:reserve_redeem"] += 1
        return {"benefit": benefit_id, "wallet_account": account_id}

    async def issue_wallet_benefit(self, account_id: str, amount: int) -> dict:
        until = (datetime.now(timezone.utc) + timedelta(days=7)).strftime("%Y-%m-%dT%H:%M:%SZ")
        currency = self.prov.currency()
        body = {"accountId": account_id, "benefitType": "BALANCE", "balanceType": "PROMOTION_CREDIT",
                "amount": {"currency": currency, "minorUnits": amount}, "issuanceSource": "MANUAL_OPS",
                "applicableScope": {"scopeType": "ANY_TRIP", "currency": currency},
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
                     {"orderId": order_id, "price": {"currency": self.prov.currency(), "minorUnits": total}})
        await asyncio.sleep(self.poll_interval * 2)
        await legacy("ticket", "/api/v1/legacy/ticket_issue", {"orderId": order_id})
        await self.think()
        if self.chance("p_legacy_cancel"):
            await legacy("cancel", "/api/v1/legacy/cancel", {"orderId": order_id})
            return "legacy_cancelled"
        await legacy("execute", "/api/v1/legacy/execute", {"orderId": order_id})
        return "legacy_completed"


# ---------------------------------------------------------------------------
# scalper simulator — ticket scalper (黄牛) behavior: zero think-time,
# hot-segment targeting, multi-account rotation, retry storms
# ---------------------------------------------------------------------------


class ScalperSim:
    """Simulates a ticket scalper who grabs tickets as fast as possible.

    Key differences from a regular customer:
    - Mostly bursty pacing with occasional slowdowns to evade simple rate limits
    - Diversifies across multiple bootstrap segments/routes
    - Rotates through a pool of pre-registered accounts and source IPs
    - Mixes browser/session fingerprints between grab attempts
    - Retries quickly on purchase failure
    - Never triggers post-purchase journeys (hoarding)
    - Pre-generates identity documents to avoid verification delays
    """

    def __init__(self, cfg: dict, api: Api, reg: Registry, stats: Stats,
                 rng: random.Random, worker_idx: int):
        self.cfg = cfg
        self.scalper_cfg = cfg.get("scalper", {})
        self.api = api
        self.reg = reg
        self.stats = stats
        self.rng = rng
        self.worker_idx = worker_idx
        self._discovery_prov = Providers(cfg, api, reg, stats, rng)
        self.poll_attempts = int(cfg["polling"]["attempts"])
        self.poll_interval = float(cfg["polling"]["interval_seconds"])
        self.staff_wait = float(cfg["behavior"].get("staff_wait_seconds", 90))
        self.redis = aioredis.from_url(cfg["target"]["redis_url"], decode_responses=True)

        self.accounts_per_worker = int(self.scalper_cfg.get("accounts_per_worker", 5))
        self.target_segment_count = int(self.scalper_cfg.get("target_segments", 8))
        self.retry_on_failure = float(self.scalper_cfg.get("retry_on_failure", 0.8))
        self.retry_same_key = bool(self.scalper_cfg.get("retry_same_key", True))
        self.batch_size = int(self.scalper_cfg.get("purchase_batch_size", 4))
        self.ip_pool = self._build_ip_pool()
        self.ip_cursor = worker_idx % len(self.ip_pool)
        self.current_ip: str | None = None
        self.user_agents = self._build_user_agents()
        self.fingerprints = self._build_fingerprints()
        self.current_fingerprint: dict[str, str] | None = None
        self.burst_gap_seconds = max(
            0.0, float(self.scalper_cfg.get("burst_gap_ms", 50)) / 1000.0)
        self.slowdown_probability = max(
            0.0, min(1.0, float(self.scalper_cfg.get("slowdown_probability", 0.12))))
        self.slowdown_min_seconds = max(
            0.0, float(self.scalper_cfg.get("slowdown_min_ms", 250)) / 1000.0)
        self.slowdown_max_seconds = max(
            self.slowdown_min_seconds,
            float(self.scalper_cfg.get("slowdown_max_ms", 1600)) / 1000.0)

        # runtime state filled by prepare()
        self.account_pool: list[dict] = []
        self.account_cursor: int = 0
        self.target_segments: list[dict] = []
        self.current_target: int = 0
        self.identity_cache: dict[str, dict[str, str]] = {}

    def _build_ip_pool(self) -> list[str]:
        configured = self.scalper_cfg.get("ip_pool")
        if isinstance(configured, list):
            pool = [str(ip).strip() for ip in configured if str(ip).strip()]
            if pool:
                return pool

        pool_size = max(1, int(self.scalper_cfg.get("ip_pool_size", 24)))
        prefix = str(self.scalper_cfg.get("ip_prefix", "203.0.113"))
        start = int(self.scalper_cfg.get("ip_start", 10))
        return [f"{prefix}.{start + offset}" for offset in range(pool_size)]

    def _build_user_agents(self) -> list[str]:
        configured = self.scalper_cfg.get("user_agents")
        if isinstance(configured, list):
            agents = [str(agent).strip() for agent in configured if str(agent).strip()]
        else:
            agents = []
        if not agents:
            agents = [
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 "
                "(KHTML, like Gecko) Version/17.5 Safari/605.1.15",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) "
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 "
                "Mobile/15E148 Safari/604.1",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            ]
        return agents

    def _build_fingerprints(self) -> list[dict[str, str]]:
        languages = ["zh-CN,zh;q=0.9", "zh-CN,zh;q=0.8,en;q=0.6", "en-US,en;q=0.7"]
        platforms = ['"Windows"', '"macOS"', '"Linux"', '"Android"', '"iOS"']
        fingerprints = []
        pool_size = max(len(self.user_agents), int(self.scalper_cfg.get("fingerprint_pool_size", 12)))
        for index in range(pool_size):
            user_agent = self.user_agents[(self.worker_idx + index) % len(self.user_agents)]
            session_id = uuid7().replace("-", "")[:20]
            fingerprints.append({
                "User-Agent": user_agent,
                "Accept-Language": languages[index % len(languages)],
                "Sec-CH-UA-Platform": platforms[index % len(platforms)],
                "DNT": "1" if index % 3 == 0 else "0",
                "Cookie": f"tt_session={session_id}; tt_fp={hashlib.sha256(session_id.encode()).hexdigest()[:16]}",
            })
        return fingerprints

    def _mix_session_fingerprint(self) -> None:
        base = dict(self.rng.choice(self.fingerprints))
        nonce = uuid7().replace("-", "")[:10]
        base["Cookie"] = f"{base['Cookie']}; tt_try={nonce}"
        base["X-Client-Trace"] = f"sc-{self.worker_idx}-{nonce}"
        self.current_fingerprint = base

    def _next_headers(self, headers: dict | None = None) -> dict:
        next_ip = self.ip_pool[self.ip_cursor % len(self.ip_pool)]
        self.ip_cursor += 1
        if self.current_ip is not None and self.current_ip != next_ip:
            self.stats.scalper_ip_rotations += 1
        self.current_ip = next_ip

        merged = dict(self.current_fingerprint or self.rng.choice(self.fingerprints))
        merged.update(headers or {})
        merged["X-Forwarded-For"] = next_ip
        return merged

    async def request(self, *args: Any, **kwargs: Any) -> tuple[int, Any]:
        headers = self._next_headers(kwargs.pop("headers", None))
        return await self.api.request(*args, headers=headers, **kwargs)

    async def burst_pause(self) -> None:
        if self.rng.random() < self.slowdown_probability:
            await asyncio.sleep(self.rng.uniform(self.slowdown_min_seconds, self.slowdown_max_seconds))
            return
        if self.burst_gap_seconds <= 0:
            return
        await asyncio.sleep(self.rng.uniform(0.0, self.burst_gap_seconds))

    # -- setup ---------------------------------------------------------------

    async def prepare(self) -> None:
        """Register accounts and pre-verify identities before the grab loop."""
        for i in range(self.accounts_per_worker):
            account_id = f"acc-scalper-{self.worker_idx}-{i}-{uuid7()}"
            await self.request("POST", "account", "/api/v1/accounts",
                                   {"accountId": account_id},
                                   ok=(200, 201), step="scalper-register")
            entry = await self.reg.add_account(account_id)
            given, family = rand_name(self.rng)
            _, data = await self.request(
                "POST", "traveler-profile", "/api/v1/travelers",
                {"accountId": account_id,
                 "travelerType": "ADULT",
                 "givenName": given, "familyName": family},
                step="scalper-create-traveler")
            tvl = data["travelerId"]
            entry["travelers"].append(tvl)
            # pre-verify identity
            identity_refs = await self._ensure_identity_verified(tvl)
            self.identity_cache[tvl] = identity_refs
            self.account_pool.append(entry)

        # live discovery of bookable segments across popular city pairs
        discovered = await self.discover_hot_segments()
        if discovered:
            self.target_segments = discovered
        else:
            async with self.reg.lock:
                routes = list(self.reg.routes)
            self.target_segments = self._diversified_segments(routes)

    def _diversified_segments(self, routes: list[dict]) -> list[dict]:
        if not routes:
            return []
        buckets: dict[tuple[str, str], list[dict]] = defaultdict(list)
        for route in routes:
            key = (str(route.get("origin_place", "")), str(route.get("dest_place", "")))
            buckets[key].append(route)
        diversified: list[dict] = []
        while len(diversified) < self.target_segment_count and buckets:
            for key in list(buckets):
                bucket = buckets[key]
                if bucket:
                    diversified.append(bucket.pop(0))
                    if len(diversified) >= self.target_segment_count:
                        break
                if not bucket:
                    del buckets[key]
        return diversified

    async def discover_hot_segments(self) -> list[dict]:
        """Scalper scouts for high-demand routes by searching popular city
        pairs across upcoming dates.  Uses the read-only discovery provider
        (no scalper headers — scouting is passive).
        """
        places = self.reg.places
        if len(places) < 2:
            return []
        segments: list[dict] = []
        codes = list(places.keys())
        pairs = [(a, b) for a in codes for b in codes if a != b]
        self.rng.shuffle(pairs)
        for origin, dest in pairs[:8]:
            date = self._discovery_prov.departure_date()
            try:
                _, data = await self.api.request(
                    "POST", "trip-planning", "/api/v1/itineraries/search",
                    {"originRef": places[origin],
                     "destinationRef": places[dest],
                     "departureDate": date,
                     "travelerRefs": [f"tvl-scout-{uuid7()[:8]}"],
                     "channel": "WEB"},
                    ok=(200,), step="scalper-discover")
            except StepFailed:
                continue
            for itin in data.get("itineraries") or []:
                if not _bookable(itin):
                    continue
                leg = itin["legs"][0]
                segments.append({
                    "origin_place": places[origin],
                    "dest_place": places[dest],
                    "date": date,
                    "scheduled_service": leg.get("servicePlanRef"),
                    "origin_node": leg.get("originStopRef"),
                    "dest_node": leg.get("destinationStopRef"),
                })
                if len(segments) >= self.target_segment_count:
                    return segments
        return segments

    def next_account(self) -> dict:
        """Round-robin through the account pool."""
        if not self.account_pool:
            raise StepFailed("scalper-account", "no accounts in pool")
        entry = self.account_pool[self.account_cursor % len(self.account_pool)]
        self.account_cursor += 1
        return entry

    async def _ensure_identity_verified(self, traveler_id: str) -> dict[str, str]:
        """Same as CustomerSim.ensure_identity_verified but without instance binding."""
        tail = str(self.rng.randint(0, 5))
        doc = f"loadgen-{traveler_id}-{tail}"
        document_hash = hashlib.sha256(doc.encode()).hexdigest() + tail
        name_hash = hashlib.sha256(("name-" + traveler_id).encode()).hexdigest()
        valid_until = (datetime.now(timezone.utc) + timedelta(days=365)).replace(microsecond=0)
        credential_body = {
            "travelerId": traveler_id, "profileSnapshotVersion": "loadgen-v1",
            "documentType": "ID_CARD",
            "maskedDocumentNo": f"LG***********{tail}",
            "documentHash": document_hash,
            "canonicalNameHash": name_hash,
            "validUntil": iso(valid_until),
        }
        _, credential = await self.request(
            "POST", "identity-verification",
            "/api/v1/identity-verification/credentials",
            credential_body, ok=(200, 201), step="scalper-identity-credential")
        material = "|".join([name_hash, "ID_CARD", document_hash, "",
                             iso(valid_until), "", "loadgen-v1"])
        verify_body = {
            "travelerId": traveler_id,
            "credentialRecordId": credential["credentialRecordId"],
            "purpose": "ORDER_CREATION",
            "materialFingerprint": hashlib.sha256(material.encode()).hexdigest(),
            "simPolicyVersion": "sim-tail-v1",
            "requestedAt": now_iso(),
        }
        _, case = await self.request(
            "POST", "identity-verification",
            "/api/v1/identity-verification/verification-cases",
            verify_body, ok=(200, 201), step="scalper-identity-verify")
        return {"identity_credential": credential["credentialRecordId"],
                "identity_case": case["verificationCaseId"]}

    # -- poll helpers (same as CustomerSim) -----------------------------------

    async def poll_order(self, order_id: str, want: set[str],
                         give_up_on_block: bool = False,
                         timeout_seconds: float | None = None) -> str | None:
        status = None
        deadline = time.time() + timeout_seconds if timeout_seconds is not None else None
        attempts = 0
        while attempts < self.poll_attempts or deadline is not None:
            attempts += 1
            code, data = await self.request(
                "GET", "journey-order",
                f"/api/v1/journey-orders/{order_id}",
                ok=(), step="scalper-poll-order")
            if code == 200:
                status = data.get("status")
                if status in want:
                    return status
                if give_up_on_block and status and "BLOCK" in status:
                    return status
            if deadline is not None and time.time() >= deadline:
                break
            await asyncio.sleep(self.poll_interval)
        return status

    async def discover_booking_saga(self, order_id: str) -> str:
        """Find the booking saga for an order without using staff workers."""
        saga = None
        for _ in range(self.poll_attempts):
            try:
                entries = await self.redis.xrevrange(
                    "events:booking-orchestration", count=500)
                for _mid, fields in entries:
                    raw = fields.get("envelope", "")
                    if "BookingSagaStarted" not in raw or order_id not in raw:
                        continue
                    try:
                        env = json.loads(raw)
                    except json.JSONDecodeError:
                        continue
                    payload = env.get("payload", {})
                    if env.get("eventType") == "BookingSagaStarted" and \
                            payload.get("journeyOrderId") == order_id:
                        saga = payload.get("sagaId")
                        break
                if saga:
                    return saga
            except Exception:
                pass
            await asyncio.sleep(self.poll_interval)
        raise StepFailed("scalper-reservation", f"no BookingSagaStarted for {order_id}")

    async def saga_failed_no_capacity(self, saga: str) -> bool:
        for _ in range(self.poll_attempts):
            code, data = await self.request(
                "GET", "booking-orchestration",
                f"/api/v1/internal/booking-sagas/{saga}",
                ok=(), step="scalper-poll-reservation")
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

    async def request_inline_reservation(self, order_id: str, segment: str,
                                         traveler: str) -> tuple[str, str, bool]:
        saga = await self.discover_booking_saga(order_id)
        sb = f"sb-{uuid7()}"
        await self.request(
            "POST", "booking-orchestration",
            f"/api/v1/internal/booking-sagas/{saga}/request-reservation",
            {"segmentRef": segment, "travelerRef": traveler, "segmentBookingId": sb},
            ok=(200,), step="scalper-reservation")
        no_capacity = await self.saga_failed_no_capacity(saga)
        return saga, sb, no_capacity

    async def issue_inline_ticket(self, order_id: str, sb: str, segment: str,
                                  traveler: str) -> str:
        _, data = await self.request(
            "POST", "entitlement-ticketing", "/api/v1/entitlements",
            {"segmentBookingId": sb, "journeyOrderId": order_id,
             "travelerRef": traveler, "segmentRef": segment,
             "issuePurpose": "INITIAL"},
            step="scalper-ticketing")
        return data["entitlementId"]

    # -- core grab journey ----------------------------------------------------

    async def journey_scalper_grab(self) -> str:
        """Single scalper grab attempt.

        login -> search hot segment -> quote -> offer -> order ->
        saga discovery -> reservation -> payment -> capture -> ticketing ->
        confirm.  Zero think time and no staff workers.
        """
        self.stats.scalper_attempts += 1
        self._mix_session_fingerprint()
        acct = self.next_account()
        tvl = acct["travelers"][0] if acct["travelers"] else None
        if tvl is None:
            raise StepFailed("scalper-grab", "account has no traveler")

        if not self.target_segments:
            raise StepFailed("scalper-grab", "no target segments available")
        seg_route = self.target_segments[self.current_target % len(self.target_segments)]
        if self.rng.random() < 0.25:
            self.current_target = self.rng.randrange(len(self.target_segments))
        else:
            self.current_target = (self.current_target + 1) % len(self.target_segments)

        channel = "WEB"
        # search -- bursty pacing with occasional slowdown
        _, search_data = await self.request(
            "POST", "trip-planning", "/api/v1/itineraries/search",
            {"originRef": seg_route["origin_place"],
             "destinationRef": seg_route["dest_place"],
             "departureDate": seg_route["date"],
             "travelerRefs": [tvl], "channel": channel},
            ok=(200,), step="scalper-search")
        await self.burst_pause()
        itins = [i for i in (search_data.get("itineraries") or []) if _bookable(i)]
        if not itins:
            self.stats.scalper_exhausted += 1
            self.current_target += 1
            return "no_itinerary"
        itin = self.rng.choice(itins)
        leg = itin["legs"][0]
        found = {"itinerary": itin["itineraryRef"],
                 "segment": leg["serviceSegmentRef"],
                 "service": leg.get("servicePlanRef") or seg_route.get("scheduled_service"),
                 "origin_node": leg.get("originStopRef") or seg_route.get("origin_node"),
                 "dest_node": leg.get("destinationStopRef") or seg_route.get("dest_node")}

        # quote -- no abandonment, evasive pacing
        _, fare_quote = await self.request(
            "POST", "fare-pricing", "/api/v1/fare-quotes",
            {"travelerRefs": [tvl], "channel": channel,
             "segmentRefs": [found["segment"]]}, step="scalper-quote")
        await self.burst_pause()

        # offer (retry on 422 — quote event propagation delay)
        offer = None
        for _so_try in range(10):
            try:
                _, offer = await self.request(
                    "POST", "offer-management", "/api/v1/offers",
                    {"accountId": acct["account_id"], "channelId": channel,
                     "itineraryRef": found["itinerary"], "travelerRefs": [tvl]},
                    step="scalper-offer")
                break
            except StepFailed as exc:
                if "422" not in str(exc) or _so_try == 9:
                    raise
                await asyncio.sleep(min(1.0 * (1.5 ** _so_try), 5.0))
        await self.burst_pause()

        # order
        _, order = await self.request(
            "POST", "journey-order", "/api/v1/journey-orders",
            {"accountId": acct["account_id"], "offerId": offer["offerId"],
             "offerVersion": offer.get("offerVersion", 1),
             "travelerRefs": [tvl], "segmentRefs": [found["segment"]],
             "journeyDate": seg_route["date"], "productCode": "TRAIN"},
            step="scalper-order")
        order_id = order["orderId"]
        await self.burst_pause()

        # risk gate: do a single immediate check, then drive the chain inline.
        status = await self.poll_order(
            order_id, set(), give_up_on_block=True, timeout_seconds=0.0)
        if status is not None and "BLOCK" in status:
            self.stats.scalper_blocked += 1
            # try next account on risk block
            return "risk_blocked"

        # reservation inline: discover saga from Redis and request the hold.
        saga, sb, no_capacity = await self.request_inline_reservation(
            order_id, found["segment"], tvl)

        if no_capacity:
            self.stats.scalper_exhausted += 1
            self.current_target += 1
            return "capacity_exhausted"

        # payment -- evasive pacing
        total_minor = int(offer["total"]["minorUnits"])
        _, intent = await self.request(
            "POST", "payment", "/api/v1/payment-intents",
            {"businessRef": order_id, "purpose": "purchase",
             "amount": {"currency": self._discovery_prov.currency(), "minorUnits": total_minor},
             "payerRef": acct["account_id"]}, step="scalper-payment-intent")
        await self.burst_pause()

        # capture
        await self.request(
            "POST", "payment",
            f"/api/v1/payment-intents/{intent['paymentIntentId']}/capture",
            {"channelRef": {"channel": "ALIPAY_SIM"}},
            ok=(200, 201, 202), step="scalper-payment-capture")
        await self.burst_pause()

        # ticketing inline: issue the entitlement directly without staff queues.
        ent = await self.issue_inline_ticket(order_id, sb, found["segment"], tvl)

        # confirm: CONFIRMING means the purchase chain completed and risk event
        # propagation is still catching up, so count it as scalper success.
        final = await self.poll_order(
            order_id, {"CONFIRMED", "CONFIRMING"}, timeout_seconds=90.0)
        if final not in {"CONFIRMED", "CONFIRMING"}:
            raise StepFailed("scalper-confirm", f"order {order_id} ended {final}")

        purchase = Purchase(
            order=order_id, saga=saga, sb=sb,
            seg=found["segment"], traveler=tvl,
            account=acct["account_id"], entitlement=ent,
            total_minor=total_minor, offer=offer.get("offerId", ""),
            payment_intent=intent.get("paymentIntentId", ""),
            itinerary=found.get("itinerary", ""),
            quote=fare_quote.get("quoteId", ""))
        await self.reg.add_purchase(purchase)
        self.stats.scalper_success += 1
        return "grabbed"

    async def close(self) -> None:
        await self.redis.aclose()


async def scalper_worker(idx: int, cfg: dict, sim: ScalperSim,
                         stats: Stats, stop: asyncio.Event) -> None:
    """Run one scalper worker: prepare accounts, then grab in a tight loop."""
    try:
        await sim.prepare()
    except Exception as exc:
        print(f"[scalper{idx}] prepare failed — {type(exc).__name__}: {str(exc)[:180]}")
        return

    batch_size = int(cfg.get("scalper", {}).get("purchase_batch_size", 4))
    retry_prob = float(cfg.get("scalper", {}).get("retry_on_failure", 0.8))

    while not stop.is_set():
        for _ in range(batch_size):
            if stop.is_set():
                break
            try:
                outcome = await sim.journey_scalper_grab()
                stats.journeys[f"scalper:{outcome}"] += 1
                if outcome == "capacity_exhausted" and \
                        sim.current_target >= len(sim.target_segments):
                    # all target segments exhausted
                    print(f"[scalper{idx}] all target segments exhausted, idling")
                    try:
                        await asyncio.wait_for(stop.wait(), timeout=30.0)
                    except asyncio.TimeoutError:
                        # refresh targets from registry
                        async with sim.reg.lock:
                            sim.target_segments = sim._diversified_segments(list(sim.reg.routes))
                        sim.current_target = 0
                    break
            except StepFailed as exc:
                stats.journeys[f"scalper:failed"] += 1
                stats.errors[f"scalper:{exc.step}"] += 1
                print(f"[scalper{idx}] grab failed — {exc}")
                if sim.rng.random() >= retry_prob:
                    break  # give up for this batch
            except Exception as exc:
                stats.journeys["scalper:crashed"] += 1
                print(f"[scalper{idx}] crashed — {type(exc).__name__}: {str(exc)[:180]}")
                break
        # burst pacing: tiny randomized pause before the next batch.
        if not stop.is_set():
            timeout = sim.rng.uniform(0.0, sim.burst_gap_seconds)
            if timeout <= 0:
                await asyncio.sleep(0)
            else:
                try:
                    await asyncio.wait_for(stop.wait(), timeout=timeout)
                except asyncio.TimeoutError:
                    pass


# ---------------------------------------------------------------------------
# low-frequency operations simulator — read-heavy backoffice coverage
# ---------------------------------------------------------------------------


class OpsSim:
    def __init__(self, cfg: dict, api: Api, reg: Registry, stats: Stats, rng: random.Random):
        self.cfg = cfg.get("ops", {})
        self._defaults = cfg.get("defaults", {})
        self.api = api
        self.reg = reg
        self.stats = stats
        self.rng = rng

    def _currency(self) -> str:
        return str(self._defaults.get("currency", "CNY"))

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
             "amount": {"currency": self._currency(), "minorUnits": amount}, "issuanceSource": "MANUAL_OPS",
             "applicableScope": {"scopeType": "ANY_TRIP", "currency": self._currency()},
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
            _, created = await api.request("POST", "place-network", "/api/v1/places",
                                     {"canonicalName": city["name"], "placeType": "CITY",
                                      "code": city["code"], "timezone": "Asia/Shanghai"},
                                     step="create-place")
            places[city["code"]] = created["placeId"]
        reg.places[city["code"]] = places[city["code"]]
        _, n = await api.request("POST", "place-network", "/api/v1/transport-nodes",
                                 {"placeId": places[city["code"]],
                                  "displayName": f"{city['name']} Station", "servingModes": ["RAIL"]},
                                 step="create-node")
        nodes[city["code"]] = n.get("nodeId") or n.get("transportNodeId")

    known = {(r["origin_place"], r["dest_place"], r["date"]) for r in reg.routes}
    base = int(bs.get("service_number_base", 5000))
    codes = list(places.keys())
    seq = 0
    for date in compute_departure_dates(cfg):
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
        for date in compute_departure_dates(cfg):
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
# schedule publisher — periodically ensures services for the rolling window
# ---------------------------------------------------------------------------


async def schedule_publisher(cfg: dict, api: Api, reg: Registry,
                             rng: random.Random,
                             stop: asyncio.Event) -> None:
    """Ops-side: publish new scheduled services for the rolling window.

    Runs hourly.  For each date in the departure window that has no known
    routes, creates new scheduled services (same logic as bootstrap).
    """
    bs = cfg.get("bootstrap") or {}
    if not bs.get("enabled", True):
        return
    base = int(bs.get("service_number_base", 5000))
    services_per_date = int(bs.get("services_per_date", 1))
    while not stop.is_set():
        try:
            await asyncio.wait_for(stop.wait(), timeout=3600.0)
            return
        except asyncio.TimeoutError:
            pass
        try:
            dates = compute_departure_dates(cfg)
            codes = list(reg.places.keys())
            if len(codes) < 2:
                continue
            known = {(r["origin_place"], r["dest_place"], r["date"])
                     for r in reg.routes}
            nodes: dict[str, str] = {}
            for code in codes:
                place_id = reg.places[code]
                _, n = await api.request(
                    "POST", "place-network", "/api/v1/transport-nodes",
                    {"placeId": place_id,
                     "displayName": f"{code} Station",
                     "servingModes": ["RAIL"]},
                    step="schedule-node")
                nodes[code] = n.get("nodeId") or n.get("transportNodeId")
            seq = 0
            for date in dates:
                for _ in range(services_per_date):
                    a, b = rng.sample(codes, 2)
                    key = (reg.places[a], reg.places[b], date)
                    seq += 1
                    if key in known:
                        continue
                    number = f"G{base + len(reg.routes) + seq}"
                    dep = f"{date}T{rng.randrange(6, 18):02d}:00:00Z"
                    arr = f"{date}T{rng.randrange(19, 23):02d}:30:00Z"
                    _, ss = await api.request(
                        "POST", "service-plan",
                        "/api/v1/scheduled-services",
                        {"carrierId": f"car-{uuid7()}",
                         "serviceNumber": number,
                         "departureTime": dep, "arrivalTime": arr,
                         "originNodeId": nodes[a],
                         "destinationNodeId": nodes[b]},
                        step="schedule-service")
                    ss_id = (ss.get("scheduledServiceRef")
                             or ss.get("scheduledServiceId"))
                    await api.request(
                        "POST", "service-plan",
                        "/api/v1/service-segments",
                        {"scheduledServiceRef": ss_id,
                         "originStopRef": nodes[a],
                         "destinationStopRef": nodes[b],
                         "departureTime": dep,
                         "arrivalTime": arr},
                        step="schedule-segment")
                    reg.routes.append({
                        "origin_place": reg.places[a],
                        "dest_place": reg.places[b],
                        "date": date,
                        "service_number": number,
                    })
                    known.add(key)
            print(f"[schedule-publisher] routes: {len(reg.routes)}")
        except Exception as exc:
            print(f"[schedule-publisher] failed: {type(exc).__name__}: "
                  f"{str(exc)[:180]}")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------


async def customer_worker(idx: int, cfg: dict, api: Api, reg: Registry,
                          stats: Stats, seed: float,
                          stop: asyncio.Event) -> None:
    """Each worker gets its own Providers + CustomerSim so persona overlays
    are isolated between concurrent workers.
    """
    rng = random.Random(seed)
    prov = Providers(cfg, api, reg, stats, rng)
    sim = CustomerSim(cfg, api, reg, stats, rng, prov)
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
        "loyalty": sim.journey_loyalty,
        "insurance": sim.journey_insurance,
        "group_booking": sim.journey_group_booking,
        "corporate": sim.journey_corporate,
        "campaign": sim.journey_campaign,
    }
    pause = cfg["run"]["session_pause_seconds"]
    try:
        while not stop.is_set():
            prov.apply_persona(prov.pick_persona())
            name = weighted_choice(rng, prov.journey_mix)
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
                                       timeout=rng.uniform(float(pause["min"]), float(pause["max"])))
            except asyncio.TimeoutError:
                pass
    finally:
        await sim.close()


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
    ops = OpsSim(cfg, api, reg, stats, rng)

    try:
        await bootstrap(cfg, api, reg, rng)
    except Exception as exc:
        print(f"[bootstrap] failed (continuing with {len(reg.routes)} "
              f"known routes): {exc}")

    # scalper actors (created after bootstrap so target_segments can resolve)
    scalper_sims: list[ScalperSim] = []
    scalper_cfg = cfg.get("scalper", {})
    if scalper_cfg.get("enabled", True):
        for i in range(int(scalper_cfg.get("workers", 3))):
            scalper_sims.append(
                ScalperSim(cfg, api, reg, stats,
                           random.Random(rng.random()), i))

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    duration = float(cfg["run"].get("duration_seconds") or 0)
    if duration > 0:
        loop.call_later(duration, stop.set)

    # per-worker CustomerSim instances — each gets its own Providers so
    # persona overlays are isolated between concurrent workers
    tasks = [asyncio.create_task(
                 customer_worker(i, cfg, api, reg, stats,
                                 rng.random(), stop))
             for i in range(int(cfg["run"]["workers"]))]
    tasks += [asyncio.create_task(staff.worker(i, stop))
              for i in range(int(cfg["staff"]["workers"]))]
    if ops.enabled():
        tasks.append(asyncio.create_task(ops.worker(stop)))
    for i, sc in enumerate(scalper_sims):
        tasks.append(asyncio.create_task(
            scalper_worker(i, cfg, sc, stats, stop)))
    tasks.append(asyncio.create_task(reporter(cfg, stats, reg, stop)))
    tasks.append(asyncio.create_task(
        schedule_publisher(cfg, api, reg, rng, stop)))

    await stop.wait()
    await asyncio.gather(*tasks, return_exceptions=True)
    reg.save(cfg["run"].get("state_file") or "")
    print("[final] " + json.dumps(stats.snapshot(), sort_keys=True),
          flush=True)
    await staff.close()
    for sc in scalper_sims:
        await sc.close()
    await api.close()


if __name__ == "__main__":
    asyncio.run(main())
