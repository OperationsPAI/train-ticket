import unittest
from datetime import datetime, timezone

from trip_planning import create_app
from trip_planning.application import search_itineraries, search_itineraries_from_payload, trip_plan_to_dto
from trip_planning.domain import (
    AvailabilityHint,
    Itinerary,
    LegCandidate,
    PreferenceConstraints,
    PriceHint,
    TripIntent,
    TripPlanningValidationError,
)


CAPTURED = datetime(2026, 7, 3, 12, 0, tzinfo=timezone.utc)


def leg(
    segment_ref: str,
    origin: str,
    destination: str,
    depart: str,
    arrive: str,
    mode: str = "train",
) -> LegCandidate:
    return LegCandidate(
        service_plan_ref="sp:2026:summer",
        service_segment_ref=segment_ref,
        origin_stop_ref=origin,
        destination_stop_ref=destination,
        departure_time=depart,
        arrival_time=arrive,
        mode=mode,
        stop_refs=(origin, destination),
        segment_refs=(segment_ref,),
    )


def price(amount: int) -> PriceHint:
    return PriceHint(
        amount_minor=amount,
        currency="USD",
        snapshot_ref=f"fare-snapshot:{amount}",
        captured_at=CAPTURED,
        confidence=80,
    )


def availability(status: str = "available_hint") -> AvailabilityHint:
    return AvailabilityHint(
        status=status,
        snapshot_ref=f"availability-snapshot:{status}",
        captured_at=CAPTURED,
        confidence=70,
    )


class TripIntentValidationTest(unittest.TestCase):
    def test_rejects_same_origin_destination(self) -> None:
        with self.assertRaisesRegex(TripPlanningValidationError, "origin and destination"):
            TripIntent(
                origin_ref="station:A",
                destination_ref="station:A",
                departure_window_start="2026-08-01T08:00:00+00:00",
                departure_window_end="2026-08-01T10:00:00+00:00",
                passenger_count=1,
            )

    def test_rejects_non_positive_passenger_count(self) -> None:
        with self.assertRaisesRegex(TripPlanningValidationError, "passenger_count"):
            TripIntent(
                origin_ref="station:A",
                destination_ref="station:B",
                departure_window_start="2026-08-01T08:00:00+00:00",
                departure_window_end="2026-08-01T10:00:00+00:00",
                passenger_count=0,
            )

    def test_rejects_invalid_time_window(self) -> None:
        with self.assertRaisesRegex(TripPlanningValidationError, "departure window"):
            TripIntent(
                origin_ref="station:A",
                destination_ref="station:B",
                departure_window_start="2026-08-01T10:00:00+00:00",
                departure_window_end="2026-08-01T08:00:00+00:00",
                passenger_count=1,
            )


class ItineraryValidationTest(unittest.TestCase):
    def test_builds_candidate_from_upstream_references(self) -> None:
        itinerary = Itinerary(
            legs=(leg("seg:1", "station:A", "station:B", "2026-08-01T08:10:00+00:00", "2026-08-01T09:00:00+00:00"),),
            price_hint=price(2500),
            availability_hint=availability(),
            planning_snapshot_refs=("place-graph:v1", "service-plan:v1"),
        )
        self.assertEqual(itinerary.origin_ref, "station:A")
        self.assertEqual(itinerary.destination_ref, "station:B")
        self.assertFalse(itinerary.price_hint.is_offer)
        self.assertFalse(itinerary.availability_hint.inventory_locked)
        self.assertIn("not an offer", itinerary.price_hint.disclaimer)
        self.assertIn("not an inventory lock", itinerary.availability_hint.disclaimer)

    def test_rejects_unordered_overlapping_legs(self) -> None:
        with self.assertRaisesRegex(TripPlanningValidationError, "ordered and non-overlapping"):
            Itinerary(
                legs=(
                    leg("seg:1", "station:A", "station:B", "2026-08-01T08:00:00+00:00", "2026-08-01T09:00:00+00:00"),
                    leg("seg:2", "station:B", "station:C", "2026-08-01T08:55:00+00:00", "2026-08-01T10:00:00+00:00"),
                )
            )

    def test_rejects_disconnected_legs(self) -> None:
        with self.assertRaisesRegex(TripPlanningValidationError, "connect at the same stop"):
            Itinerary(
                legs=(
                    leg("seg:1", "station:A", "station:B", "2026-08-01T08:00:00+00:00", "2026-08-01T09:00:00+00:00"),
                    leg("seg:2", "station:X", "station:C", "2026-08-01T09:15:00+00:00", "2026-08-01T10:00:00+00:00"),
                )
            )

    def test_rejects_unordered_stop_pattern(self) -> None:
        with self.assertRaisesRegex(TripPlanningValidationError, "ordered stop_refs"):
            leg(
                "seg:1",
                "station:A",
                "station:B",
                "2026-08-01T08:00:00+00:00",
                "2026-08-01T09:00:00+00:00",
            ).__class__(
                service_plan_ref="sp:2026:summer",
                service_segment_ref="seg:1",
                origin_stop_ref="station:A",
                destination_stop_ref="station:B",
                departure_time="2026-08-01T08:00:00+00:00",
                arrival_time="2026-08-01T09:00:00+00:00",
                stop_refs=("station:B", "station:A"),
                segment_refs=("seg:1",),
            )


class SearchFoundationTest(unittest.TestCase):
    def intent(self) -> TripIntent:
        return TripIntent(
            origin_ref="station:A",
            destination_ref="station:C",
            departure_window_start="2026-08-01T08:00:00+00:00",
            departure_window_end="2026-08-01T12:00:00+00:00",
            passenger_count=2,
            preferences=PreferenceConstraints(
                allowed_modes=frozenset({"train"}),
                max_connections=1,
                min_connection_minutes=10,
                max_price_minor=5000,
                prefer_low_price=True,
            ),
        )

    def test_search_returns_ranked_dto_without_locking_inventory(self) -> None:
        direct = Itinerary(
            legs=(leg("seg:direct", "station:A", "station:C", "2026-08-01T09:00:00+00:00", "2026-08-01T10:00:00+00:00"),),
            price_hint=price(2000),
            availability_hint=availability("limited_hint"),
        )
        slower = Itinerary(
            legs=(leg("seg:slow", "station:A", "station:C", "2026-08-01T09:10:00+00:00", "2026-08-01T11:00:00+00:00"),),
            price_hint=price(4500),
            availability_hint=availability("available_hint"),
        )
        result = search_itineraries(self.intent(), (slower, direct), generated_at=CAPTURED)
        dto = trip_plan_to_dto(result)
        self.assertEqual([c[0].itinerary_ref for c in result.candidates][0], direct.itinerary_ref)
        self.assertEqual(dto["candidates"][0]["priceHint"]["isOffer"], False)
        self.assertEqual(dto["candidates"][0]["availabilityHint"]["inventoryLocked"], False)
        self.assertIn("not offers", dto["boundaryNotice"])
        self.assertIn("price hint", dto["candidates"][0]["score"]["explanation"])

    def test_impossible_connection_window_is_excluded(self) -> None:
        candidate = Itinerary(
            legs=(
                leg("seg:1", "station:A", "station:B", "2026-08-01T08:00:00+00:00", "2026-08-01T09:00:00+00:00"),
                leg("seg:2", "station:B", "station:C", "2026-08-01T09:05:00+00:00", "2026-08-01T10:00:00+00:00"),
            ),
            price_hint=price(3000),
            availability_hint=availability(),
        )
        result = search_itineraries(self.intent(), (candidate,), generated_at=CAPTURED)
        self.assertEqual(result.candidates, ())
        self.assertEqual(result.exclusions[0].code, "IMPOSSIBLE_CONNECTION_WINDOW")

    def test_payload_adapter_accepts_fastapi_safe_dicts(self) -> None:
        payload = {
            "intent": {
                "originRef": "station:A",
                "destinationRef": "station:C",
                "departureWindowStart": "2026-08-01T08:00:00+00:00",
                "departureWindowEnd": "2026-08-01T12:00:00+00:00",
                "passengerCount": 1,
                "preferences": {"allowedModes": ["train"], "minConnectionMinutes": 5},
            },
            "candidates": [
                {
                    "legs": [
                        {
                            "servicePlanRef": "sp:2026:summer",
                            "serviceSegmentRef": "seg:direct",
                            "originStopRef": "station:A",
                            "destinationStopRef": "station:C",
                            "departureTime": "2026-08-01T09:00:00+00:00",
                            "arrivalTime": "2026-08-01T10:00:00+00:00",
                            "mode": "train",
                            "stopRefs": ["station:A", "station:C"],
                            "segmentRefs": ["seg:direct"],
                        }
                    ],
                    "priceHint": {
                        "amountMinor": 1800,
                        "currency": "USD",
                        "snapshotRef": "fare-snapshot:1800",
                        "capturedAt": "2026-07-03T12:00:00+00:00",
                    },
                    "availabilityHint": {
                        "status": "available_hint",
                        "snapshotRef": "availability-snapshot:1",
                        "capturedAt": "2026-07-03T12:00:00+00:00",
                    },
                }
            ],
        }
        dto = search_itineraries_from_payload(payload)
        self.assertEqual(len(dto["candidates"]), 1)
        self.assertFalse(dto["candidates"][0]["priceHint"]["isOffer"])

    def test_fastapi_search_route_is_registered(self) -> None:
        app = create_app()
        routes = {route.path for route in app.routes}
        self.assertIn("/search", routes)


if __name__ == "__main__":
    unittest.main()
