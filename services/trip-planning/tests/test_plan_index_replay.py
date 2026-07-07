import unittest

from fastapi.testclient import TestClient

import trip_planning.api as api
from trip_planning import create_app
from trip_planning.adapters.messaging.fake import FakeEventPublisher, FakeEventSubscriber
from trip_planning.api import PlanStore
from trip_planning.events import EventEnvelope


class PlanIndexReplayTest(unittest.TestCase):
    def setUp(self) -> None:
        api._plan_store.clear()

    def tearDown(self) -> None:
        api._plan_store.clear()

    def test_new_process_loads_db_read_model_before_subscribing_incrementals(self) -> None:
        plan_store = PlanStore()
        for envelope in _standard_api_sequence_events():
            plan_store.apply_envelope(envelope)
        publisher = FakeEventPublisher()
        subscriber = FakeEventSubscriber()
        original = api._active_plan_store
        original_plan_store = api._plan_store
        api._active_plan_store = plan_store
        api._plan_store = plan_store

        app = create_app(event_publisher=publisher, event_subscriber=subscriber)
        try:
            with TestClient(app) as client:
                response = client.post(
                    "/api/v1/itineraries/search",
                    json={
                        "originRef": "plc-origin",
                        "destinationRef": "plc-destination",
                        "departureDate": "2026-08-01",
                        "travelerRefs": ["tvl-1"],
                        "channel": "WEB",
                    },
                )
        finally:
            api._active_plan_store = original
            api._plan_store = original_plan_store

        self.assertEqual(response.status_code, 200)
        leg = response.json()["itineraries"][0]["legs"][0]
        self.assertEqual(leg["serviceSegmentRef"], "seg-real-080")
        self.assertEqual(leg["originStopRef"], "node-origin-new")
        self.assertEqual(leg["destinationStopRef"], "node-destination-new")

    def test_place_search_matches_any_node_for_same_place_not_first_node_only(self) -> None:
        old_nodes = [
            EventEnvelope(
                eventId="evt-old-origin-node",
                eventType="TransportNodeRegistered",
                producer="place-network",
                payload={"nodeId": "node-origin-old", "placeId": "plc-origin", "displayName": "old", "servingModes": ["TRAIN"]},
            ),
            EventEnvelope(
                eventId="evt-old-destination-node",
                eventType="TransportNodeRegistered",
                producer="place-network",
                payload={"nodeId": "node-destination-old", "placeId": "plc-destination", "displayName": "old", "servingModes": ["TRAIN"]},
            ),
        ]
        plan_store = PlanStore()
        for envelope in old_nodes + _standard_api_sequence_events():
            plan_store.apply_envelope(envelope)
        subscriber = FakeEventSubscriber()
        original = api._active_plan_store
        original_plan_store = api._plan_store
        api._active_plan_store = plan_store
        api._plan_store = plan_store

        app = create_app(event_publisher=FakeEventPublisher(), event_subscriber=subscriber)
        try:
            with TestClient(app) as client:
                response = client.post(
                    "/api/v1/itineraries/search",
                    json={
                        "originRef": "plc-origin",
                        "destinationRef": "plc-destination",
                        "departureDate": "2026-08-01",
                        "travelerRefs": ["tvl-1"],
                        "channel": "WEB",
                    },
                )
        finally:
            api._active_plan_store = original
            api._plan_store = original_plan_store

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["itineraries"][0]["legs"][0]["serviceSegmentRef"], "seg-real-080")


def _standard_api_sequence_events() -> list[EventEnvelope]:
    return [
        EventEnvelope(
            eventId="evt-origin-node",
            eventType="TransportNodeRegistered",
            producer="place-network",
            payload={"nodeId": "node-origin-new", "placeId": "plc-origin", "displayName": "origin", "servingModes": ["TRAIN"]},
        ),
        EventEnvelope(
            eventId="evt-destination-node",
            eventType="TransportNodeRegistered",
            producer="place-network",
            payload={"nodeId": "node-destination-new", "placeId": "plc-destination", "displayName": "destination", "servingModes": ["TRAIN"]},
        ),
        EventEnvelope(
            eventId="evt-scheduled-service",
            eventType="ServicePlanPublished",
            producer="service-plan",
            payload={
                "scheduledServiceRef": "ss-real-080",
                "serviceNumber": "G5080",
                "status": "PUBLISHED",
                "carrierId": "car-1",
                "departureTime": "2026-08-01T09:00:00Z",
                "arrivalTime": "2026-08-01T10:00:00Z",
                "originNodeId": "node-origin-new",
                "destinationNodeId": "node-destination-new",
            },
        ),
        EventEnvelope(
            eventId="evt-service-segment",
            eventType="ServicePlanChanged",
            producer="service-plan",
            payload={
                "segmentRef": "seg-real-080",
                "scheduledServiceRef": "ss-real-080",
                "originStopRef": "node-origin-new",
                "destinationStopRef": "node-destination-new",
                "departureTime": "2026-08-01T09:00:00Z",
                "arrivalTime": "2026-08-01T10:00:00Z",
            },
        ),
    ]


if __name__ == "__main__":
    unittest.main()
