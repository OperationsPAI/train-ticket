from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from hashlib import sha256
from typing import Any, Iterable, Mapping, Sequence


class TripPlanningValidationError(ValueError):
    """Raised when a trip-planning domain invariant is violated."""


_ALLOWED_AVAILABILITY_STATUSES = {
    "available_hint",
    "limited_hint",
    "unknown",
    "unavailable_hint",
}
_PRICE_HINT_DISCLAIMER = (
    "Price hint is a non-authoritative planning snapshot. It is not an offer, "
    "does not freeze fare rules, and cannot be used as a payment amount."
)
_AVAILABILITY_HINT_DISCLAIMER = (
    "Availability hint is a non-authoritative planning snapshot. It is not an "
    "inventory lock, hold, or sale commitment."
)


def _require_ref(value: str, field_name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise TripPlanningValidationError(f"{field_name} must be a non-empty upstream reference")
    return value.strip()


def _parse_datetime(value: datetime | str, field_name: str) -> datetime:
    if isinstance(value, datetime):
        return value
    if isinstance(value, str):
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise TripPlanningValidationError(f"{field_name} must be an ISO-8601 datetime") from exc
    raise TripPlanningValidationError(f"{field_name} must be an ISO-8601 datetime")


def _normalize_modes(modes: Iterable[str]) -> frozenset[str]:
    normalized: set[str] = set()
    for mode in modes:
        normalized.add(_require_ref(mode, "mode").lower())
    return frozenset(normalized)


def _tuple_of_refs(values: Sequence[str], field_name: str) -> tuple[str, ...]:
    refs = tuple(_require_ref(value, field_name) for value in values)
    if not refs:
        raise TripPlanningValidationError(f"{field_name} must contain at least one reference")
    if len(set(refs)) != len(refs):
        raise TripPlanningValidationError(f"{field_name} must be ordered without duplicate references")
    return refs


def stable_ref(prefix: str, parts: Iterable[str]) -> str:
    digest = sha256("|".join(parts).encode("utf-8")).hexdigest()[:16]
    return f"{prefix}_{digest}"


@dataclass(frozen=True)
class PreferenceConstraints:
    """Hard planning constraints and soft ranking preferences carried by a search."""

    allowed_modes: frozenset[str] = field(default_factory=frozenset)
    max_connections: int | None = None
    min_connection_minutes: int = 5
    max_connection_wait_minutes: int | None = None
    max_price_minor: int | None = None
    prefer_low_price: bool = False
    prefer_short_duration: bool = True
    accessibility_required: bool = False
    connection_intent: str = "self_transfer_allowed"

    def __post_init__(self) -> None:
        object.__setattr__(self, "allowed_modes", _normalize_modes(self.allowed_modes))
        if self.max_connections is not None and self.max_connections < 0:
            raise TripPlanningValidationError("max_connections cannot be negative")
        if self.min_connection_minutes < 0:
            raise TripPlanningValidationError("min_connection_minutes cannot be negative")
        if self.max_connection_wait_minutes is not None and self.max_connection_wait_minutes < 0:
            raise TripPlanningValidationError("max_connection_wait_minutes cannot be negative")
        if self.max_price_minor is not None and self.max_price_minor < 0:
            raise TripPlanningValidationError("max_price_minor cannot be negative")
        object.__setattr__(
            self,
            "connection_intent",
            _require_ref(self.connection_intent, "connection_intent"),
        )

    @classmethod
    def from_dict(cls, data: Mapping[str, Any] | None) -> "PreferenceConstraints":
        if data is None:
            return cls()
        return cls(
            allowed_modes=frozenset(data.get("allowed_modes", data.get("allowedModes", ()))),
            max_connections=data.get("max_connections", data.get("maxConnections")),
            min_connection_minutes=data.get(
                "min_connection_minutes", data.get("minConnectionMinutes", 5)
            ),
            max_connection_wait_minutes=data.get(
                "max_connection_wait_minutes", data.get("maxConnectionWaitMinutes")
            ),
            max_price_minor=data.get("max_price_minor", data.get("maxPriceMinor")),
            prefer_low_price=bool(data.get("prefer_low_price", data.get("preferLowPrice", False))),
            prefer_short_duration=bool(
                data.get("prefer_short_duration", data.get("preferShortDuration", True))
            ),
            accessibility_required=bool(
                data.get("accessibility_required", data.get("accessibilityRequired", False))
            ),
            connection_intent=data.get(
                "connection_intent", data.get("connectionIntent", "self_transfer_allowed")
            ),
        )


@dataclass(frozen=True)
class TripIntent:
    """Search-only trip intent owned by Trip Planning."""

    origin_ref: str
    destination_ref: str
    departure_window_start: datetime
    departure_window_end: datetime
    passenger_count: int
    preferences: PreferenceConstraints = field(default_factory=PreferenceConstraints)

    def __post_init__(self) -> None:
        object.__setattr__(self, "origin_ref", _require_ref(self.origin_ref, "origin_ref"))
        object.__setattr__(
            self, "destination_ref", _require_ref(self.destination_ref, "destination_ref")
        )
        if self.origin_ref == self.destination_ref:
            raise TripPlanningValidationError("origin and destination must be different")
        object.__setattr__(
            self,
            "departure_window_start",
            _parse_datetime(self.departure_window_start, "departure_window_start"),
        )
        object.__setattr__(
            self,
            "departure_window_end",
            _parse_datetime(self.departure_window_end, "departure_window_end"),
        )
        if self.departure_window_start >= self.departure_window_end:
            raise TripPlanningValidationError("departure window start must be before end")
        if self.passenger_count <= 0:
            raise TripPlanningValidationError("passenger_count must be positive")

    @classmethod
    def from_dict(cls, data: Mapping[str, Any]) -> "TripIntent":
        return cls(
            origin_ref=data.get("origin_ref", data.get("originRef")),
            destination_ref=data.get("destination_ref", data.get("destinationRef")),
            departure_window_start=data.get(
                "departure_window_start", data.get("departureWindowStart")
            ),
            departure_window_end=data.get("departure_window_end", data.get("departureWindowEnd")),
            passenger_count=int(data.get("passenger_count", data.get("passengerCount", 0))),
            preferences=PreferenceConstraints.from_dict(data.get("preferences")),
        )

    def fingerprint_parts(self) -> tuple[str, ...]:
        return (
            self.origin_ref,
            self.destination_ref,
            self.departure_window_start.isoformat(),
            self.departure_window_end.isoformat(),
            str(self.passenger_count),
            ",".join(sorted(self.preferences.allowed_modes)),
            str(self.preferences.max_connections),
            str(self.preferences.min_connection_minutes),
            str(self.preferences.max_connection_wait_minutes),
            str(self.preferences.max_price_minor),
            self.preferences.connection_intent,
        )


@dataclass(frozen=True)
class PriceHint:
    """Non-authoritative price snapshot used only for search ranking/explanation."""

    amount_minor: int
    currency: str
    snapshot_ref: str
    captured_at: datetime
    source: str = "fare-pricing"
    confidence: int = 50
    is_offer: bool = False
    inventory_locked: bool = False
    disclaimer: str = _PRICE_HINT_DISCLAIMER

    def __post_init__(self) -> None:
        if self.amount_minor < 0:
            raise TripPlanningValidationError("amount_minor cannot be negative")
        currency = _require_ref(self.currency, "currency").upper()
        if len(currency) != 3:
            raise TripPlanningValidationError("currency must be a 3-letter ISO code")
        object.__setattr__(self, "currency", currency)
        object.__setattr__(self, "snapshot_ref", _require_ref(self.snapshot_ref, "snapshot_ref"))
        object.__setattr__(self, "captured_at", _parse_datetime(self.captured_at, "captured_at"))
        object.__setattr__(self, "source", _require_ref(self.source, "source"))
        if not 0 <= self.confidence <= 100:
            raise TripPlanningValidationError("confidence must be between 0 and 100")
        if self.is_offer or self.inventory_locked:
            raise TripPlanningValidationError("price hints cannot be offers or inventory locks")

    @classmethod
    def from_dict(cls, data: Mapping[str, Any] | None) -> "PriceHint | None":
        if not data:
            return None
        return cls(
            amount_minor=int(data.get("amount_minor", data.get("amountMinor"))),
            currency=data["currency"],
            snapshot_ref=data.get("snapshot_ref", data.get("snapshotRef")),
            captured_at=data.get("captured_at", data.get("capturedAt")),
            source=data.get("source", "fare-pricing"),
            confidence=int(data.get("confidence", 50)),
        )


@dataclass(frozen=True)
class AvailabilityHint:
    """Non-authoritative availability snapshot used only for planning."""

    status: str
    snapshot_ref: str
    captured_at: datetime
    source: str = "capacity-availability"
    confidence: int = 50
    not_authoritative: bool = True
    inventory_locked: bool = False
    disclaimer: str = _AVAILABILITY_HINT_DISCLAIMER

    def __post_init__(self) -> None:
        status = _require_ref(self.status, "status").lower()
        if status not in _ALLOWED_AVAILABILITY_STATUSES:
            raise TripPlanningValidationError(f"unsupported availability hint status: {status}")
        object.__setattr__(self, "status", status)
        object.__setattr__(self, "snapshot_ref", _require_ref(self.snapshot_ref, "snapshot_ref"))
        object.__setattr__(self, "captured_at", _parse_datetime(self.captured_at, "captured_at"))
        object.__setattr__(self, "source", _require_ref(self.source, "source"))
        if not 0 <= self.confidence <= 100:
            raise TripPlanningValidationError("confidence must be between 0 and 100")
        if not self.not_authoritative or self.inventory_locked:
            raise TripPlanningValidationError("availability hints cannot be authoritative locks")

    @classmethod
    def from_dict(cls, data: Mapping[str, Any] | None) -> "AvailabilityHint | None":
        if not data:
            return None
        return cls(
            status=data.get("status", "unknown"),
            snapshot_ref=data.get("snapshot_ref", data.get("snapshotRef")),
            captured_at=data.get("captured_at", data.get("capturedAt")),
            source=data.get("source", "capacity-availability"),
            confidence=int(data.get("confidence", 50)),
        )


@dataclass(frozen=True)
class LegCandidate:
    """A service-plan-like leg reference with ordered stop and segment references."""

    service_plan_ref: str
    service_segment_ref: str
    origin_stop_ref: str
    destination_stop_ref: str
    departure_time: datetime
    arrival_time: datetime
    mode: str = "train"
    stop_refs: tuple[str, ...] = field(default_factory=tuple)
    segment_refs: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        for field_name in (
            "service_plan_ref",
            "service_segment_ref",
            "origin_stop_ref",
            "destination_stop_ref",
        ):
            object.__setattr__(self, field_name, _require_ref(getattr(self, field_name), field_name))
        object.__setattr__(self, "mode", _require_ref(self.mode, "mode").lower())
        object.__setattr__(
            self, "departure_time", _parse_datetime(self.departure_time, "departure_time")
        )
        object.__setattr__(self, "arrival_time", _parse_datetime(self.arrival_time, "arrival_time"))
        if self.departure_time >= self.arrival_time:
            raise TripPlanningValidationError("leg departure_time must be before arrival_time")
        stop_refs = self.stop_refs or (self.origin_stop_ref, self.destination_stop_ref)
        stop_refs = _tuple_of_refs(stop_refs, "stop_refs")
        if stop_refs[0] != self.origin_stop_ref or stop_refs[-1] != self.destination_stop_ref:
            raise TripPlanningValidationError(
                "ordered stop_refs must start at origin_stop_ref and end at destination_stop_ref"
            )
        object.__setattr__(self, "stop_refs", stop_refs)
        segment_refs = self.segment_refs or (self.service_segment_ref,)
        object.__setattr__(self, "segment_refs", _tuple_of_refs(segment_refs, "segment_refs"))

    @classmethod
    def from_dict(cls, data: Mapping[str, Any]) -> "LegCandidate":
        return cls(
            service_plan_ref=data.get("service_plan_ref", data.get("servicePlanRef")),
            service_segment_ref=data.get("service_segment_ref", data.get("serviceSegmentRef")),
            origin_stop_ref=data.get("origin_stop_ref", data.get("originStopRef")),
            destination_stop_ref=data.get("destination_stop_ref", data.get("destinationStopRef")),
            departure_time=data.get("departure_time", data.get("departureTime")),
            arrival_time=data.get("arrival_time", data.get("arrivalTime")),
            mode=data.get("mode", "train"),
            stop_refs=tuple(data.get("stop_refs", data.get("stopRefs", ()))),
            segment_refs=tuple(data.get("segment_refs", data.get("segmentRefs", ()))),
        )

    @property
    def leg_ref(self) -> str:
        return stable_ref(
            "leg",
            (
                self.service_plan_ref,
                self.service_segment_ref,
                self.origin_stop_ref,
                self.destination_stop_ref,
                self.departure_time.isoformat(),
                self.arrival_time.isoformat(),
            ),
        )

    @property
    def duration_minutes(self) -> int:
        return int((self.arrival_time - self.departure_time).total_seconds() // 60)


@dataclass(frozen=True)
class Itinerary:
    """Search candidate assembled from upstream references. It never locks inventory."""

    legs: tuple[LegCandidate, ...]
    itinerary_ref: str | None = None
    price_hint: PriceHint | None = None
    availability_hint: AvailabilityHint | None = None
    planning_snapshot_refs: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        legs = tuple(self.legs)
        if not legs:
            raise TripPlanningValidationError("itinerary must contain at least one leg")
        for previous, current in zip(legs, legs[1:]):
            if previous.arrival_time > current.departure_time:
                raise TripPlanningValidationError("itinerary legs must be ordered and non-overlapping")
            if previous.destination_stop_ref != current.origin_stop_ref:
                raise TripPlanningValidationError("adjacent legs must connect at the same stop reference")
        object.__setattr__(self, "legs", legs)
        snapshot_refs = tuple(_require_ref(ref, "planning_snapshot_refs") for ref in self.planning_snapshot_refs)
        object.__setattr__(self, "planning_snapshot_refs", snapshot_refs)
        if self.itinerary_ref is None:
            object.__setattr__(self, "itinerary_ref", stable_ref("itin", self.fingerprint_parts()))
        else:
            object.__setattr__(self, "itinerary_ref", _require_ref(self.itinerary_ref, "itinerary_ref"))

    @classmethod
    def from_dict(cls, data: Mapping[str, Any]) -> "Itinerary":
        return cls(
            itinerary_ref=data.get("itinerary_ref", data.get("itineraryRef")),
            legs=tuple(LegCandidate.from_dict(item) for item in data.get("legs", ())),
            price_hint=PriceHint.from_dict(data.get("price_hint", data.get("priceHint"))),
            availability_hint=AvailabilityHint.from_dict(
                data.get("availability_hint", data.get("availabilityHint"))
            ),
            planning_snapshot_refs=tuple(
                data.get("planning_snapshot_refs", data.get("planningSnapshotRefs", ()))
            ),
        )

    @property
    def origin_ref(self) -> str:
        return self.legs[0].origin_stop_ref

    @property
    def destination_ref(self) -> str:
        return self.legs[-1].destination_stop_ref

    @property
    def departure_time(self) -> datetime:
        return self.legs[0].departure_time

    @property
    def arrival_time(self) -> datetime:
        return self.legs[-1].arrival_time

    @property
    def duration_minutes(self) -> int:
        return int((self.arrival_time - self.departure_time).total_seconds() // 60)

    @property
    def connection_count(self) -> int:
        return len(self.legs) - 1

    def connection_waits_minutes(self) -> tuple[int, ...]:
        return tuple(
            int((current.departure_time - previous.arrival_time).total_seconds() // 60)
            for previous, current in zip(self.legs, self.legs[1:])
        )

    def fingerprint_parts(self) -> tuple[str, ...]:
        return tuple(part for leg in self.legs for part in leg.segment_refs) + tuple(
            leg.departure_time.isoformat() for leg in self.legs
        )


@dataclass(frozen=True)
class ExclusionReason:
    code: str
    message: str
    itinerary_ref: str | None = None
    details: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ScoreComponent:
    name: str
    value: int
    weight: int
    contribution: int
    explanation: str


@dataclass(frozen=True)
class PlanningScore:
    total: int
    profile_version: str
    components: tuple[ScoreComponent, ...]
    explanation: str
