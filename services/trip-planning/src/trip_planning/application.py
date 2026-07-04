from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any, Mapping, Sequence

from .domain import (
    AvailabilityHint,
    ExclusionReason,
    Itinerary,
    PlanningScore,
    PriceHint,
    ScoreComponent,
    TripIntent,
    TripPlanningValidationError,
    stable_ref,
)

RANKING_PROFILE_VERSION = "trip-planning-search-v1"


@dataclass(frozen=True)
class CandidateEvaluation:
    itinerary: Itinerary | None
    score: PlanningScore | None
    exclusions: tuple[ExclusionReason, ...]


@dataclass(frozen=True)
class TripPlanResult:
    trip_plan_ref: str
    intent_ref: str
    generated_at: datetime
    ranking_profile_version: str
    candidates: tuple[tuple[Itinerary, PlanningScore], ...]
    exclusions: tuple[ExclusionReason, ...]
    boundary_notice: str = (
        "Trip Planning returns search candidates only. Results are not offers, "
        "do not lock capacity, and do not create orders, payments, or tickets."
    )


def _exclusion(code: str, message: str, itinerary: Itinerary | None = None, **details: Any) -> ExclusionReason:
    return ExclusionReason(
        code=code,
        message=message,
        itinerary_ref=itinerary.itinerary_ref if itinerary else None,
        details=details,
    )


def validate_itinerary_for_intent(intent: TripIntent, itinerary: Itinerary) -> tuple[ExclusionReason, ...]:
    """Return hard-constraint rejections without mutating or reserving anything."""

    exclusions: list[ExclusionReason] = []
    if itinerary.origin_ref != intent.origin_ref:
        exclusions.append(
            _exclusion("ORIGIN_MISMATCH", "candidate does not start at requested origin", itinerary)
        )
    if itinerary.destination_ref != intent.destination_ref:
        exclusions.append(
            _exclusion(
                "DESTINATION_MISMATCH", "candidate does not end at requested destination", itinerary
            )
        )
    if not (intent.departure_window_start <= itinerary.departure_time <= intent.departure_window_end):
        exclusions.append(
            _exclusion(
                "DEPARTURE_OUTSIDE_WINDOW",
                "candidate departure is outside requested time window",
                itinerary,
                departure_time=itinerary.departure_time.isoformat(),
            )
        )
    if intent.preferences.allowed_modes:
        disallowed = sorted(
            {leg.mode for leg in itinerary.legs if leg.mode not in intent.preferences.allowed_modes}
        )
        if disallowed:
            exclusions.append(
                _exclusion(
                    "MODE_NOT_ALLOWED",
                    "candidate uses transport modes outside requested constraints",
                    itinerary,
                    modes=disallowed,
                )
            )
    if (
        intent.preferences.max_connections is not None
        and itinerary.connection_count > intent.preferences.max_connections
    ):
        exclusions.append(
            _exclusion(
                "TOO_MANY_CONNECTIONS",
                "candidate exceeds requested maximum connections",
                itinerary,
                connection_count=itinerary.connection_count,
            )
        )
    for wait in itinerary.connection_waits_minutes():
        if wait < intent.preferences.min_connection_minutes:
            exclusions.append(
                _exclusion(
                    "IMPOSSIBLE_CONNECTION_WINDOW",
                    "candidate connection wait is shorter than the minimum connection window",
                    itinerary,
                    wait_minutes=wait,
                    minimum_minutes=intent.preferences.min_connection_minutes,
                )
            )
        if (
            intent.preferences.max_connection_wait_minutes is not None
            and wait > intent.preferences.max_connection_wait_minutes
        ):
            exclusions.append(
                _exclusion(
                    "CONNECTION_WAIT_TOO_LONG",
                    "candidate connection wait exceeds requested maximum",
                    itinerary,
                    wait_minutes=wait,
                    maximum_minutes=intent.preferences.max_connection_wait_minutes,
                )
            )
    if (
        intent.preferences.max_price_minor is not None
        and itinerary.price_hint is not None
        and itinerary.price_hint.amount_minor > intent.preferences.max_price_minor
    ):
        exclusions.append(
            _exclusion(
                "PRICE_HINT_OVER_BUDGET",
                "non-authoritative price hint exceeds requested budget",
                itinerary,
                amount_minor=itinerary.price_hint.amount_minor,
            )
        )
    if itinerary.availability_hint and itinerary.availability_hint.status == "UNAVAILABLE":
        exclusions.append(
            _exclusion(
                "UNAVAILABLE_SNAPSHOT_HINT",
                "non-authoritative availability snapshot says the candidate is likely unavailable",
                itinerary,
            )
        )
    return tuple(exclusions)


def rank_itinerary(intent: TripIntent, itinerary: Itinerary) -> PlanningScore:
    """Produce a deterministic, auditable score. Higher is better."""

    components: list[ScoreComponent] = []

    duration_value = max(0, 1440 - itinerary.duration_minutes)
    duration_weight = 2 if intent.preferences.prefer_short_duration else 1
    components.append(
        ScoreComponent(
            name="duration",
            value=duration_value,
            weight=duration_weight,
            contribution=duration_value * duration_weight,
            explanation=f"{itinerary.duration_minutes} minute end-to-end duration",
        )
    )

    transfer_value = max(0, 500 - itinerary.connection_count * 120)
    components.append(
        ScoreComponent(
            name="connections",
            value=transfer_value,
            weight=1,
            contribution=transfer_value,
            explanation=f"{itinerary.connection_count} connection(s)",
        )
    )

    if itinerary.price_hint is not None:
        budget = intent.preferences.max_price_minor or max(itinerary.price_hint.amount_minor, 1)
        price_value = max(0, 1000 - int((itinerary.price_hint.amount_minor / budget) * 1000))
        price_weight = 2 if intent.preferences.prefer_low_price else 1
        components.append(
            ScoreComponent(
                name="price_hint",
                value=price_value,
                weight=price_weight,
                contribution=price_value * price_weight,
                explanation=(
                    f"non-authoritative {itinerary.price_hint.currency} "
                    f"{itinerary.price_hint.amount_minor} price hint"
                ),
            )
        )
    else:
        components.append(
            ScoreComponent(
                name="price_hint",
                value=50,
                weight=1,
                contribution=50,
                explanation="no price hint; candidate remains searchable but less auditable",
            )
        )

    availability_value = {
        None: 80,
        "AVAILABLE": 200,
        "LIMITED": 120,
        "UNKNOWN": 60,
        "UNAVAILABLE": 0,
    }[itinerary.availability_hint.status if itinerary.availability_hint else None]
    components.append(
        ScoreComponent(
            name="availability_hint",
            value=availability_value,
            weight=1,
            contribution=availability_value,
            explanation="non-authoritative availability snapshot only; no inventory lock",
        )
    )

    total = sum(component.contribution for component in components)
    return PlanningScore(
        total=total,
        profile_version=RANKING_PROFILE_VERSION,
        components=tuple(components),
        explanation=(
            f"Deterministic score {total} from duration, connection count, price hint, "
            "and availability hint. Hints are snapshots, not offers or locks."
        ),
    )


def evaluate_candidates(intent: TripIntent, candidates: Sequence[Itinerary]) -> tuple[CandidateEvaluation, ...]:
    evaluations: list[CandidateEvaluation] = []
    for candidate in candidates:
        exclusions = validate_itinerary_for_intent(intent, candidate)
        if exclusions:
            evaluations.append(CandidateEvaluation(None, None, exclusions))
        else:
            evaluations.append(CandidateEvaluation(candidate, rank_itinerary(intent, candidate), ()))
    return tuple(evaluations)


def search_itineraries(
    intent: TripIntent,
    candidates: Sequence[Itinerary],
    *,
    generated_at: datetime | None = None,
) -> TripPlanResult:
    """FastAPI-safe application function for search-only trip planning.

    It accepts already-normalized upstream candidate references, applies Trip
    Planning invariants and ranking, and returns DTO-ready results without
    creating offers, capacity holds, orders, payments, tickets, or provider calls.
    """

    generated_at = generated_at or datetime.now(timezone.utc)
    evaluations = evaluate_candidates(intent, candidates)
    accepted: list[tuple[Itinerary, PlanningScore]] = []
    exclusions: list[ExclusionReason] = []
    for evaluation in evaluations:
        if evaluation.itinerary and evaluation.score:
            accepted.append((evaluation.itinerary, evaluation.score))
        exclusions.extend(evaluation.exclusions)
    accepted.sort(
        key=lambda pair: (
            -pair[1].total,
            pair[0].arrival_time.isoformat(),
            pair[0].itinerary_ref or "",
        )
    )
    intent_ref = stable_ref("intent", intent.fingerprint_parts())
    trip_plan_ref = stable_ref(
        "tripplan",
        (intent_ref, generated_at.isoformat(), RANKING_PROFILE_VERSION)
        + tuple(itinerary.itinerary_ref or "" for itinerary, _score in accepted),
    )
    return TripPlanResult(
        trip_plan_ref=trip_plan_ref,
        intent_ref=intent_ref,
        generated_at=generated_at,
        ranking_profile_version=RANKING_PROFILE_VERSION,
        candidates=tuple(accepted),
        exclusions=tuple(exclusions),
    )


def _datetime_to_iso(value: Any) -> Any:
    if isinstance(value, datetime):
        return value.isoformat()
    return value


def _dataclass_to_public_dict(value: Any) -> Any:
    if isinstance(value, tuple):
        return [_dataclass_to_public_dict(item) for item in value]
    if isinstance(value, list):
        return [_dataclass_to_public_dict(item) for item in value]
    if isinstance(value, dict):
        return {key: _dataclass_to_public_dict(item) for key, item in value.items()}
    if hasattr(value, "__dataclass_fields__"):
        return {key: _dataclass_to_public_dict(item) for key, item in asdict(value).items()}
    return _datetime_to_iso(value)


def itinerary_to_dto(itinerary: Itinerary, score: PlanningScore) -> dict[str, Any]:
    return {
        "itineraryRef": itinerary.itinerary_ref,
        "originRef": itinerary.origin_ref,
        "destinationRef": itinerary.destination_ref,
        "departureTime": itinerary.departure_time.isoformat(),
        "arrivalTime": itinerary.arrival_time.isoformat(),
        "durationMinutes": itinerary.duration_minutes,
        "connectionCount": itinerary.connection_count,
        "legs": [
            {
                "legRef": leg.leg_ref,
                "mode": leg.mode,
                "servicePlanRef": leg.service_plan_ref,
                "serviceSegmentRef": leg.service_segment_ref,
                "originStopRef": leg.origin_stop_ref,
                "destinationStopRef": leg.destination_stop_ref,
                "departureTime": leg.departure_time.isoformat(),
                "arrivalTime": leg.arrival_time.isoformat(),
                "stopRefs": list(leg.stop_refs),
                "segmentRefs": list(leg.segment_refs),
            }
            for leg in itinerary.legs
        ],
        "priceHint": _hint_to_dto(itinerary.price_hint),
        "availabilityHint": _hint_to_dto(itinerary.availability_hint),
        "planningSnapshotRefs": list(itinerary.planning_snapshot_refs),
        "score": _dataclass_to_public_dict(score),
        "notices": [
            "Search result only: not an Offer.",
            "No inventory is held or locked by Trip Planning.",
        ],
    }


def _hint_to_dto(hint: PriceHint | AvailabilityHint | None) -> dict[str, Any] | None:
    if hint is None:
        return None
    dto = _dataclass_to_public_dict(hint)
    # Preserve explicit boundary flags in API DTOs even if field names differ by hint type.
    dto["isOffer"] = False
    dto["inventoryLocked"] = False
    return dto


def trip_plan_to_dto(result: TripPlanResult) -> dict[str, Any]:
    return {
        "tripPlanRef": result.trip_plan_ref,
        "intentRef": result.intent_ref,
        "generatedAt": result.generated_at.isoformat(),
        "rankingProfileVersion": result.ranking_profile_version,
        "boundaryNotice": result.boundary_notice,
        "candidates": [itinerary_to_dto(itinerary, score) for itinerary, score in result.candidates],
        "exclusions": [_dataclass_to_public_dict(exclusion) for exclusion in result.exclusions],
    }


def search_itineraries_from_payload(payload: Mapping[str, Any]) -> dict[str, Any]:
    """JSON-dict adapter safe for FastAPI request handlers and tests."""

    try:
        intent = TripIntent.from_dict(payload["intent"])
        candidates = tuple(Itinerary.from_dict(item) for item in payload.get("candidates", ()))
        return trip_plan_to_dto(search_itineraries(intent, candidates))
    except KeyError as exc:
        raise TripPlanningValidationError(f"missing required field: {exc.args[0]}") from exc
