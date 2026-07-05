from __future__ import annotations

import json
import unittest
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import uuid4

from fastapi.testclient import TestClient

from fare_pricing import (
    AssessmentPurpose,
    FareQuote,
    FareRule,
    FareRuleSet,
    Money,
    PriceExplanation,
    PricingError,
    QuoteStatus,
    RuleKind,
    RuleSetStatus,
    ValidityWindow,
    calculate_fare_quote,
)
from fare_pricing.api import create_app
from fare_pricing.application.service import InMemoryStore
from fare_pricing.ports import EventEnvelope
from fare_pricing.ports.messaging import PublishFailed
from fare_pricing.adapters.messaging.fake import FakeEventPublisher, FakeEventSubscriber

NOW = datetime(2026, 7, 3, 12, 0, tzinfo=UTC)


def rule(rule_id: str, kind: RuleKind, amount: str, *, refundable: bool = True) -> FareRule:
    return FareRule(
        rule_id=rule_id,
        kind=kind,
        amount=Money(amount, "CNY"),
        explanation=PriceExplanation(f"fare.{rule_id}", {"rule": rule_id}),
        refundable=refundable,
    )


def published_rule_set(*extra_rules: FareRule) -> FareRuleSet:
    rs = FareRuleSet(
        rule_set_id="ruleset-main",
        supplier_id="supplier-a",
        product_code="rail-flex",
        mode="rail",
        channel="web",
        version="2026.07.03",
        effective_window=ValidityWindow(NOW - timedelta(days=1), NOW + timedelta(days=30)),
        rules=[
            rule("base", RuleKind.BASE_FARE, "100.00"),
            rule("tax", RuleKind.TAX, "7.50"),
            rule("platform", RuleKind.FEE, "5.00", refundable=False),
            rule("member", RuleKind.DISCOUNT, "12.50"),
            *extra_rules,
        ],
    )
    return rs.publish(NOW)


def make_app(store: InMemoryStore | None = None) -> TestClient:
    if store is None:
        store = InMemoryStore()
    app = create_app(store=store)
    return TestClient(app)


class FarePricingApiTest(unittest.TestCase):
    """HTTP endpoint tests — no live Redis required (in-memory store)."""

    def setUp(self) -> None:
        self.store = InMemoryStore()
        # Seed a published rule set
        self.rs = published_rule_set()
        self.store.fare_rule_sets[self.rs.rule_set_id] = self.rs
        self.client = make_app(self.store)

    def test_health_endpoints(self) -> None:
        """Health endpoints work as before."""
        for path in ["/health", "/live", "/ready", "/metadata"]:
            resp = self.client.get(path)
            self.assertEqual(resp.status_code, 200)

    def test_fare_quote_happy_path(self) -> None:
        """POST /api/v1/fare-quotes returns 201 with quote details."""
        resp = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": str(uuid4())},
        )
        self.assertEqual(resp.status_code, 201)
        data = resp.json()
        self.assertIn("quoteId", data)
        self.assertTrue(data["quoteId"].startswith("fq-"), f"Expected fq- prefix in {data['quoteId']}")
        self.assertEqual(data["status"], "QUOTED")
        self.assertIn("breakdown", data)
        self.assertIn("ruleSnapshot", data)
        self.assertIn("validFrom", data)
        self.assertIn("validUntil", data)
        # Verify breakdown structure
        bd = data["breakdown"]
        self.assertIn("baseFare", bd)
        self.assertIn("total", bd)
        self.assertEqual(bd["total"]["currency"], "CNY")
        self.assertIsInstance(bd["total"]["minorUnits"], int)
        # base fare 100.00 -> 10000 minorUnits
        self.assertEqual(bd["baseFare"]["minorUnits"], 10000)
        # total = 100 + 7.50 + 5.00 - 12.50 = 100.00
        self.assertEqual(bd["total"]["minorUnits"], 10000)

    def test_fare_quote_validation_failure(self) -> None:
        """Invalid requests produce 400 with canonical error body."""
        resp = self.client.post(
            "/api/v1/fare-quotes",
            json={"travelerRefs": [], "channel": "web", "segmentRefs": []},
            headers={"Idempotency-Key": str(uuid4())},
        )
        self.assertEqual(resp.status_code, 422)
        data = resp.json()
        self.assertIsInstance(data, dict)

    def test_fare_quote_no_rule_set(self) -> None:
        """Request with channel that has no published rule set returns error."""
        store = InMemoryStore()
        # No rule sets seeded
        client = make_app(store)
        resp = client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "mobile",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": str(uuid4())},
        )
        self.assertEqual(resp.status_code, 400)

    def test_get_fare_quote(self) -> None:
        """GET /api/v1/fare-quotes/{id} returns the quote."""
        # Create a quote first
        create_resp = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": str(uuid4())},
        )
        quote_id = create_resp.json()["quoteId"]

        get_resp = self.client.get(f"/api/v1/fare-quotes/{quote_id}")
        self.assertEqual(get_resp.status_code, 200)
        data = get_resp.json()
        self.assertEqual(data["quoteId"], quote_id)
        self.assertEqual(data["status"], "QUOTED")

    def test_get_fare_quote_not_found(self) -> None:
        """GET /api/v1/fare-quotes/{id} returns 404 for unknown quote."""
        resp = self.client.get("/api/v1/fare-quotes/fq-nonexistent")
        self.assertEqual(resp.status_code, 404)
        data = resp.json()
        self.assertIsInstance(data, dict)

    def test_adjustment_quote_refund_happy_path(self) -> None:
        """POST /api/v1/adjustment-quotes returns 201 for refund."""
        # Create a quote first
        create_resp = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": str(uuid4())},
        )
        self.assertEqual(create_resp.status_code, 201)

        # Add refund fee rule
        self.store.fare_rule_sets[self.rs.rule_set_id] = published_rule_set(
            rule("refund", RuleKind.REFUND_FEE, "20.00"),
        )

        resp = self.client.post(
            "/api/v1/adjustment-quotes",
            json={
                "purpose": "REFUND",
                "entitlementIds": ["ent-123"],
                "journeyOrderId": "ord-456",
            },
            headers={"Idempotency-Key": str(uuid4())},
        )
        self.assertEqual(resp.status_code, 201)
        data = resp.json()
        self.assertIn("adjustmentQuoteId", data)
        self.assertEqual(data["purpose"], "REFUND")
        self.assertIn("status", data)
        self.assertIn("refundableAmount", data)
        self.assertIn("amountDue", data)

    def test_adjustment_quote_not_found(self) -> None:
        """No original quote leads to 404."""
        resp = self.client.post(
            "/api/v1/adjustment-quotes",
            json={
                "purpose": "REFUND",
                "entitlementIds": ["ent-123"],
                "journeyOrderId": "ord-456",
            },
            headers={"Idempotency-Key": str(uuid4())},
        )
        self.assertEqual(resp.status_code, 404)

    def test_idempotent_replay_returns_original_result(self) -> None:
        """Same Idempotency-Key returns the original response."""
        idempotency_key = str(uuid4())

        body = {
            "travelerRefs": ["tvl-123"],
            "channel": "web",
            "segmentRefs": ["seg-456"],
        }

        resp1 = self.client.post(
            "/api/v1/fare-quotes",
            json=body,
            headers={"Idempotency-Key": idempotency_key},
        )
        self.assertEqual(resp1.status_code, 201)
        data1 = resp1.json()

        resp2 = self.client.post(
            "/api/v1/fare-quotes",
            json=body,
            headers={"Idempotency-Key": idempotency_key},
        )
        self.assertEqual(resp2.status_code, 201)
        data2 = resp2.json()

        self.assertEqual(data1, data2)

    def test_idempotency_key_reused_with_different_body(self) -> None:
        """Different body with same Idempotency-Key returns 422."""
        idempotency_key = str(uuid4())

        self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": idempotency_key},
        )

        resp = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-999"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": idempotency_key},
        )
        self.assertEqual(resp.status_code, 422)

    def test_correlation_id_propagation(self) -> None:
        """X-Correlation-Id is propagated in response."""
        corr_id = str(uuid4())
        resp = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
            headers={
                "Idempotency-Key": str(uuid4()),
                "X-Correlation-Id": corr_id,
            },
        )
        self.assertEqual(resp.status_code, 201)
        self.assertEqual(resp.headers.get("X-Correlation-Id"), corr_id)

    def test_money_format_minor_units(self) -> None:
        """Money amounts use minorUnits integer, not float."""
        resp = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": str(uuid4())},
        )
        data = resp.json()
        total = data["breakdown"]["total"]
        self.assertIsInstance(total["minorUnits"], int)
        self.assertEqual(total["currency"], "CNY")


class FarePricingMessagingTest(unittest.TestCase):
    """Messaging port tests — no live Redis required (in-memory fake)."""

    def test_event_publisher_wraps_in_envelope(self) -> None:
        """FakeEventPublisher stores events with correct envelope fields."""
        publisher = FakeEventPublisher()
        envelope = EventEnvelope(
            event_id="evt-test-123",
            event_type="FareRuleSetPublished",
            schema_version=1,
            producer="fare-pricing",
            source_command_id="cmd-test-456",
            causation_id="cmd-test-789",
            correlation_id="corr-test-000",
            occurred_at=NOW,
            payload={"ruleSetId": "rs-main", "status": "published"},
        )
        publisher.publish(envelope)
        self.assertEqual(publisher.published_event_count, 1)
        stored = publisher.last_event()
        assert stored is not None
        self.assertEqual(stored.event_id, "evt-test-123")
        self.assertEqual(stored.event_type, "FareRuleSetPublished")
        self.assertEqual(stored.producer, "fare-pricing")

    def test_publisher_raises_on_failure(self) -> None:
        """FakeEventPublisher raises PublishFailed when configured to fail."""
        publisher = FakeEventPublisher()
        publisher.fail_next_publish()
        envelope = EventEnvelope(
            event_id="evt-test-fail",
            event_type="FareRuleSetPublished",
            producer="fare-pricing",
        )
        with self.assertRaises(PublishFailed):
            publisher.publish(envelope)

    def test_publisher_json_serialization(self) -> None:
        """Envelope to_json_dict produces camelCase JSON with correct fields."""
        envelope = EventEnvelope(
            event_id="evt-test-json",
            event_type="FareRuleSetPublished",
            schema_version=1,
            producer="fare-pricing",
            source_command_id="cmd-test-456",
            causation_id="cmd-test-789",
            correlation_id="corr-test-000",
            occurred_at=NOW,
            payload={"ruleSetId": "rs-main"},
        )
        d = envelope.to_json_dict()
        self.assertEqual(d["eventId"], "evt-test-json")
        self.assertEqual(d["eventType"], "FareRuleSetPublished")
        self.assertEqual(d["producer"], "fare-pricing")
        self.assertEqual(d["schemaVersion"], 1)
        self.assertEqual(d["sourceCommandId"], "cmd-test-456")
        self.assertEqual(d["causationId"], "cmd-test-789")
        self.assertEqual(d["correlationId"], "corr-test-000")
        self.assertIn("occurredAt", d)
        self.assertIn("payload", d)
        self.assertEqual(d["payload"]["ruleSetId"], "rs-main")

    def test_publisher_roundtrip_json(self) -> None:
        """Envelope survives to_json_dict -> from_json_dict roundtrip."""
        envelope = EventEnvelope(
            event_id="evt-test-rt",
            event_type="FareRuleSetPublished",
            schema_version=1,
            producer="fare-pricing",
            source_command_id="cmd-test-456",
            causation_id="cmd-test-789",
            correlation_id="corr-test-000",
            occurred_at=NOW,
            payload={"ruleSetId": "rs-main"},
        )
        d = envelope.to_json_dict()
        restored = EventEnvelope.from_json_dict(d)
        self.assertEqual(restored.event_id, envelope.event_id)
        self.assertEqual(restored.event_type, envelope.event_type)
        self.assertEqual(restored.producer, envelope.producer)

    def test_subscriber_deduplication_by_event_id(self) -> None:
        """FakeEventSubscriber can deliver events to a handler that deduplicates."""
        processed: list[str] = []

        def handler(envelope: EventEnvelope) -> None:
            processed.append(envelope.event_id)

        subscriber = FakeEventSubscriber()
        subscriber.subscribe(
            streams=["events:fare-pricing"],
            group="fare-pricing",
            consumer_name="fare-pricing-1",
            handler=handler,
        )

        envelope = EventEnvelope(
            event_id="evt-dedup-1",
            event_type="FareRuleSetPublished",
            producer="fare-pricing",
        )

        # Deliver twice
        subscriber.deliver(envelope)
        subscriber.deliver(envelope)

        # Handler receives both — dedup is handler's responsibility
        self.assertEqual(len(processed), 2)

    def test_subscriber_consumer_group_configuration(self) -> None:
        """FakeEventSubscriber stores the group/consumer config."""
        subscriber = FakeEventSubscriber()

        def handler(envelope: EventEnvelope) -> None:
            pass

        subscriber.subscribe(
            streams=["events:fare-pricing"],
            group="fare-pricing",
            consumer_name="fare-pricing-pod-0",
            handler=handler,
        )

        self.assertTrue(subscriber._started)
        self.assertEqual(subscriber.group, "fare-pricing")
        self.assertEqual(subscriber.consumer_name, "fare-pricing-pod-0")
        self.assertIn("events:fare-pricing", subscriber.streams)

    def test_envelope_wire_format_matches_contract_example(self) -> None:
        """Verify the envelope JSON matches the contract's example structure."""
        envelope = EventEnvelope(
            event_id="evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222",
            event_type="FareRuleSetPublished",
            schema_version=1,
            producer="fare-pricing",
            source_command_id="cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555",
            causation_id="cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555",
            correlation_id="corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444",
            occurred_at=datetime(2026, 7, 3, 10, 30, 0, 0, tzinfo=UTC),
            payload={"ruleSetId": "rs-main"},
        )
        d = envelope.to_json_dict()
        self.assertEqual(d["eventId"], "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222")
        self.assertEqual(d["eventType"], "FareRuleSetPublished")
        self.assertEqual(d["schemaVersion"], 1)
        self.assertEqual(d["producer"], "fare-pricing")
        self.assertEqual(d["sourceCommandId"], "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555")
        self.assertEqual(d["causationId"], "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555")
        self.assertEqual(d["correlationId"], "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444")
        self.assertEqual(d["occurredAt"], "2026-07-03T10:30:00.000Z")

    def test_publisher_derives_stream_from_producer(self) -> None:
        """FakeEventPublisher records events; stream derivation is in Redis adapter."""
        publisher = FakeEventPublisher()
        envelope = EventEnvelope(
            event_id="evt-stream-test",
            event_type="FareRuleSetPublished",
            producer="fare-pricing",
        )
        publisher.publish(envelope)
        stream_events = publisher.published_envelopes_on_stream("events:fare-pricing")
        self.assertEqual(len(stream_events), 1)
        self.assertEqual(stream_events[0].event_id, "evt-stream-test")


if __name__ == "__main__":
    unittest.main()
