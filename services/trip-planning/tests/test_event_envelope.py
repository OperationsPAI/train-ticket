import unittest
from datetime import datetime, timezone
from uuid import UUID

from trip_planning.events import EventEnvelope, build_itinerary_proposed_event


CAPTURED = datetime(2026, 7, 3, 12, 0, tzinfo=timezone.utc)


class EventEnvelopeTest(unittest.TestCase):
    def test_envelope_round_trip(self) -> None:
        envelope = EventEnvelope(
            eventId="evt-test-1",
            eventType="ItineraryProposed",
            producer="trip-planning",
            causationId="cmd-test-1",
            correlationId="corr-test-1",
            occurredAt=CAPTURED,
            payload={"intentRef": "intent-test", "itineraries": []},
        )
        json_dict = envelope.to_json_dict()
        restored = EventEnvelope.from_json_dict(json_dict)
        self.assertEqual(restored.eventId, envelope.eventId)
        self.assertEqual(restored.eventType, envelope.eventType)
        self.assertEqual(restored.producer, envelope.producer)
        self.assertEqual(restored.causationId, envelope.causationId)
        self.assertEqual(restored.correlationId, envelope.correlationId)
        self.assertEqual(restored.payload["intentRef"], "intent-test")

    def test_envelope_has_correct_id_prefix(self) -> None:
        envelope = EventEnvelope(
            eventId="evt-test-prefix",
            eventType="TestEvent",
            payload={},
        )
        self.assertTrue(envelope.eventId.startswith("evt-"))

    def test_generated_event_id_uses_uuid_v7_with_event_prefix(self) -> None:
        envelope = build_itinerary_proposed_event("intent-v7", tuple(), tuple())
        self.assertTrue(envelope.eventId.startswith("evt-"))
        self.assertEqual(UUID(envelope.eventId.removeprefix("evt-")).version, 7)

    def test_envelope_json_serialization_omits_absent_causation_id(self) -> None:
        envelope = EventEnvelope(
            eventId="evt-test-json",
            eventType="ItineraryProposed",
            payload={"key": "value"},
        )
        json_dict = envelope.to_json_dict()
        self.assertEqual(json_dict["eventId"], "evt-test-json")
        self.assertEqual(json_dict["eventType"], "ItineraryProposed")
        self.assertEqual(json_dict["payload"]["key"], "value")
        self.assertEqual(json_dict["producer"], "trip-planning")
        self.assertEqual(json_dict["schemaVersion"], 1)
        self.assertNotIn("causationId", json_dict)

    def test_envelope_json_serialization_includes_present_causation_id(self) -> None:
        envelope = EventEnvelope(
            eventId="evt-test-json",
            eventType="ItineraryProposed",
            causationId="cmd-test-json",
            payload={"key": "value"},
        )
        json_dict = envelope.to_json_dict()
        self.assertEqual(json_dict["causationId"], "cmd-test-json")


class EventEnvelopeWireFormatTest(unittest.TestCase):
    def test_envelope_to_json_uses_camel_case(self) -> None:
        envelope = EventEnvelope(
            eventId="evt-wire-1",
            eventType="ItineraryProposed",
            producer="trip-planning",
            causationId="cmd-wire",
            correlationId="corr-wire",
            occurredAt=CAPTURED,
            payload={"intentRef": "intent-wire", "itineraries": []},
        )
        json_dict = envelope.to_json_dict()
        self.assertIn("eventId", json_dict)
        self.assertIn("eventType", json_dict)
        self.assertIn("schemaVersion", json_dict)
        self.assertIn("occurredAt", json_dict)
        self.assertIn("correlationId", json_dict)
        self.assertIn("causationId", json_dict)
        self.assertIn("producer", json_dict)
        self.assertIn("payload", json_dict)

    def test_envelope_occurred_at_is_rfc3339(self) -> None:
        envelope = EventEnvelope(
            eventId="evt-rfc-1",
            eventType="Test",
            occurredAt=datetime(2026, 7, 3, 10, 30, 0, 123000, tzinfo=timezone.utc),
            payload={},
        )
        json_dict = envelope.to_json_dict()
        self.assertEqual(json_dict["occurredAt"], "2026-07-03T10:30:00.123Z")
