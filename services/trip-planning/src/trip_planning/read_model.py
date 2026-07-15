from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from .domain import AvailabilityHint, Itinerary, LegCandidate, MinimumConnectionTimeRule, PriceHint, required_connection_minutes


@dataclass(frozen=True)
class SegmentRecord:
    segment_ref: str
    scheduled_service_ref: str
    origin_stop_ref: str
    destination_stop_ref: str
    departure_time: datetime
    arrival_time: datetime


def parse_datetime(value: datetime | str) -> datetime:
    if isinstance(value, datetime):
        return value
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def segment_from_payload(segment_ref: str, payload: Mapping[str, object]) -> SegmentRecord | None:
    origin = str(payload.get("originStopRef", ""))
    destination = str(payload.get("destinationStopRef", ""))
    departure_raw = str(payload.get("departureTime", ""))
    if not segment_ref or not origin or not destination or not departure_raw:
        return None
    departure = parse_datetime(departure_raw)
    arrival = parse_datetime(str(payload.get("arrivalTime", departure_raw)))
    return SegmentRecord(
        segment_ref=segment_ref,
        scheduled_service_ref=str(payload.get("scheduledServiceRef", "")),
        origin_stop_ref=origin,
        destination_stop_ref=destination,
        departure_time=departure,
        arrival_time=arrival,
    )


def build_itineraries(
    *,
    origin_ref: str,
    destination_ref: str,
    departure_date: str,
    segments: Sequence[SegmentRecord],
    node_place: Mapping[str, str],
    node_payloads: Mapping[str, Mapping[str, object]],
    mct_rules: Sequence[MinimumConnectionTimeRule],
    default_min_connection_minutes: int = 5,
) -> list[Itinerary]:
    dated_segments = [segment for segment in segments if segment.departure_time.date().isoformat() == departure_date]
    found: list[Itinerary] = []
    seen_refs: set[str] = set()

    for segment in dated_segments:
        if _matches(segment.origin_stop_ref, origin_ref, node_place) and _matches(segment.destination_stop_ref, destination_ref, node_place):
            itinerary = _itinerary_from_segments((segment,), ())
            if itinerary.itinerary_ref not in seen_refs:
                found.append(itinerary)
                seen_refs.add(str(itinerary.itinerary_ref))

    for first in dated_segments:
        if not _matches(first.origin_stop_ref, origin_ref, node_place):
            continue
        for second in dated_segments:
            if first.segment_ref == second.segment_ref:
                continue
            if not _same_connection_point(first.destination_stop_ref, second.origin_stop_ref, node_place):
                continue
            if not _matches(second.destination_stop_ref, destination_ref, node_place):
                continue
            wait_minutes = int((second.departure_time - first.arrival_time).total_seconds() // 60)
            if wait_minutes < 0:
                continue
            required_minutes, applied_rule = required_connection_minutes(
                first.destination_stop_ref,
                second.origin_stop_ref,
                at_time=first.arrival_time,
                node_payloads=node_payloads,
                rules=mct_rules,
                default_minutes=default_min_connection_minutes,
            )
            if wait_minutes < required_minutes:
                continue
            rule_refs = () if applied_rule is None else (applied_rule.snapshot_ref,)
            itinerary = _itinerary_from_segments((first, second), rule_refs)
            if itinerary.itinerary_ref not in seen_refs:
                found.append(itinerary)
                seen_refs.add(str(itinerary.itinerary_ref))

    found.sort(key=lambda itinerary: (itinerary.departure_time, itinerary.arrival_time, itinerary.itinerary_ref or ""))
    return found


def _matches(stop_ref: str, requested: str, node_place: Mapping[str, str]) -> bool:
    requested_place = node_place.get(requested)
    stop_place = node_place.get(stop_ref)
    return (
        stop_ref == requested
        or stop_place == requested
        or (requested_place is not None and stop_ref == requested_place)
        or (requested_place is not None and stop_place == requested_place)
    )


def _same_connection_point(left_ref: str, right_ref: str, node_place: Mapping[str, str]) -> bool:
    if left_ref == right_ref:
        return True
    left_place = node_place.get(left_ref)
    right_place = node_place.get(right_ref)
    return left_place is not None and left_place == right_place


def _itinerary_from_segments(segments: tuple[SegmentRecord, ...], mct_snapshot_refs: tuple[str, ...]) -> Itinerary:
    legs = tuple(
        LegCandidate(
            service_plan_ref=segment.scheduled_service_ref,
            service_segment_ref=segment.segment_ref,
            origin_stop_ref=segment.origin_stop_ref,
            destination_stop_ref=segment.destination_stop_ref,
            departure_time=segment.departure_time,
            arrival_time=segment.arrival_time,
            mode="train",
            stop_refs=(segment.origin_stop_ref, segment.destination_stop_ref),
            segment_refs=(segment.segment_ref,),
        )
        for segment in segments
    )
    first_departure = segments[0].departure_time
    snapshot_ref = "+".join(segment.segment_ref for segment in segments)
    return Itinerary(
        legs=legs,
        price_hint=PriceHint(
            amount_minor=0,
            currency="CNY",
            snapshot_ref=f"fare-snapshot:{snapshot_ref}",
            captured_at=first_departure,
            confidence=50,
        ),
        availability_hint=AvailabilityHint(
            status="UNKNOWN",
            snapshot_ref=f"availability-snapshot:{snapshot_ref}",
            captured_at=first_departure,
            confidence=50,
        ),
        planning_snapshot_refs=tuple(f"planning-snapshot:{segment.segment_ref}" for segment in segments) + mct_snapshot_refs,
    )
