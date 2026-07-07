from __future__ import annotations

import unittest
from datetime import UTC, datetime, timedelta
from decimal import Decimal
import uuid
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
from fare_pricing.application import deterministic_rule_set_event_id
from fare_pricing.ids import uuid7
from fare_pricing.application.service import InMemoryStore
from fare_pricing.ports import EventEnvelope
from fare_pricing.ports.messaging import PublishFailed
from fare_pricing.adapters.messaging.fake import FakeEventPublisher, FakeEventSubscriber

NOW = datetime(2026, 7, 3, 12, 0, tzinfo=UTC)


def uuid7_key(suffix: int = 1) -> str:
    return f"0194f2e0-7b3e-7610-8284-{suffix:012d}"


def rule(rule_id: str, kind: RuleKind, amount: str, *, refundable: bool = True) -> FareRule:
    return FareRule(
        rule_id=rule_id,
        kind=kind,
        amount=Money(amount, "CNY"),
        explanation=PriceExplanation(f"fare.{rule_id}", {"rule": rule_id}),
        refundable=refundable,
    )


def published_rule_set(*extra_rules: FareRule, product_code: str = "rail-standard", rule_set_id: str = "ruleset-main") -> FareRuleSet:
    rs = FareRuleSet(
        rule_set_id=rule_set_id,
        supplier_id="supplier-a",
        product_code=product_code,
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


def make_app(store: InMemoryStore | None = None, publisher: FakeEventPublisher | None = None) -> TestClient:
    if store is None:
        store = InMemoryStore()
    app = create_app(store=store, event_publisher=publisher or FakeEventPublisher())
    return TestClient(app)


class FarePricingApiTest(unittest.TestCase):
    """HTTP endpoint tests — no live Redis required (in-memory store)."""

    def setUp(self) -> None:
        self.store = InMemoryStore()
        # Seed a published rule set
        self.rs = published_rule_set()
        self.store.fare_rule_sets[self.rs.rule_set_id] = self.rs
        self.publisher = FakeEventPublisher()
        self.client = make_app(self.store, self.publisher)

    def test_health_endpoints(self) -> None:
        """Health endpoints work as before."""
        for path in ["/health", "/live", "/ready", "/metadata"]:
            resp = self.client.get(path)
            self.assertEqual(resp.status_code, 200)


    def create_rule_set(
        self,
        *,
        base_minor: int = 12000,
        refund_minor: int = 3000,
        channel: str = "web",
        version: str = "2026.07.04",
        product_code: str = "rail-standard",
    ) -> dict[str, object]:
        resp = self.client.post(
            "/api/v1/fare-rule-sets",
            json={
                "supplierId": "supplier-managed",
                "contractId": "contract-managed",
                "productCode": product_code,
                "mode": "rail",
                "channel": channel,
                "version": version,
                "effectiveWindow": {
                    "startsAt": "2026-07-02T00:00:00Z",
                    "endsAt": "2026-08-02T00:00:00Z",
                },
                "rules": [
                    {
                        "ruleId": "base-managed",
                        "kind": "base_fare",
                        "amount": {"currency": "CNY", "minorUnits": base_minor},
                        "explanation": {"code": "fare.base.managed", "parameters": {"source": "api"}},
                        "refundable": True,
                    },
                    {
                        "ruleId": "refund-managed",
                        "kind": "refund_fee",
                        "amount": {"currency": "CNY", "minorUnits": refund_minor},
                        "explanation": {"code": "fare.refund.managed", "parameters": {"source": "api"}},
                        "refundable": True,
                    },
                    {
                        "ruleId": "change-managed",
                        "kind": "change_fee",
                        "amount": {"currency": "CNY", "minorUnits": 1500},
                        "explanation": {"code": "fare.change.managed", "parameters": {"source": "api"}},
                        "refundable": True,
                    },
                ],
            },
            headers={"Idempotency-Key": uuid7_key(900)},
        )
        self.assertEqual(resp.status_code, 201, resp.text)
        return resp.json()

    def test_create_and_publish_rule_set_drives_new_quotes_and_refunds(self) -> None:
        created = self.create_rule_set()
        self.assertTrue(str(created["ruleSetId"]).startswith("frs-"))
        self.assertEqual(created["status"], "DRAFT")

        publish_resp = self.client.post(
            f"/api/v1/fare-rule-sets/{created['ruleSetId']}/publish",
            json={},
            headers={"Idempotency-Key": uuid7_key(901)},
        )
        self.assertEqual(publish_resp.status_code, 200, publish_resp.text)
        self.assertEqual(publish_resp.json()["status"], "PUBLISHED")
        self.assertEqual(self.store.fare_rule_sets[self.rs.rule_set_id].status, RuleSetStatus.SUPERSEDED)

        event_types = [event.event_type for event in self.publisher.published_events]
        self.assertEqual(event_types, ["FareRuleSetPublished", "FareRuleSetSuperseded"])
        published_payload = self.publisher.published_events[0].payload
        self.assertEqual(published_payload["ruleSetId"], created["ruleSetId"])
        self.assertEqual(published_payload["contractId"], "contract-managed")
        self.assertEqual(published_payload["rules"][0]["amount"], {"currency": "CNY", "minorUnits": 12000})
        superseded_payload = self.publisher.published_events[1].payload
        self.assertEqual(superseded_payload["ruleSetId"], self.rs.rule_set_id)
        self.assertEqual(superseded_payload["supersededByRuleSetId"], created["ruleSetId"])

        quote_resp = self.client.post(
            "/api/v1/fare-quotes",
            json={"travelerRefs": ["tvl-123"], "channel": "web", "segmentRefs": ["seg-456"]},
            headers={"Idempotency-Key": uuid7_key(902)},
        )
        self.assertEqual(quote_resp.status_code, 201, quote_resp.text)
        self.assertEqual(quote_resp.json()["breakdown"]["total"], {"currency": "CNY", "minorUnits": 12000})
        self.assertEqual(quote_resp.json()["ruleSnapshot"]["ruleSetId"], created["ruleSetId"])

        refund_resp = self.client.post(
            "/api/v1/adjustment-quotes",
            json={
                "purpose": "REFUND",
                "entitlementIds": ["ent-456"],
                "journeyOrderId": "ord-456",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": uuid7_key(903)},
        )
        self.assertEqual(refund_resp.status_code, 201, refund_resp.text)
        self.assertEqual(refund_resp.json()["refundableAmount"], {"currency": "CNY", "minorUnits": 9000})

    def test_product_code_disambiguates_quotes_and_publish_supersede_scope(self) -> None:
        business = self.create_rule_set(
            base_minor=18000,
            refund_minor=4000,
            version="2026.07.business",
            product_code="rail-business",
        )
        publish_resp = self.client.post(
            f"/api/v1/fare-rule-sets/{business['ruleSetId']}/publish",
            json={},
            headers={"Idempotency-Key": uuid7_key(910)},
        )
        self.assertEqual(publish_resp.status_code, 200, publish_resp.text)
        self.assertEqual(self.store.fare_rule_sets[self.rs.rule_set_id].status, RuleSetStatus.PUBLISHED)

        standard_quote = self.client.post(
            "/api/v1/fare-quotes",
            json={"travelerRefs": ["tvl-standard"], "channel": "web", "segmentRefs": ["seg-standard"]},
            headers={"Idempotency-Key": uuid7_key(911)},
        )
        self.assertEqual(standard_quote.status_code, 201, standard_quote.text)
        self.assertEqual(standard_quote.json()["breakdown"]["total"], {"currency": "CNY", "minorUnits": 10000})
        self.assertEqual(standard_quote.json()["ruleSnapshot"]["ruleSetId"], self.rs.rule_set_id)

        business_quote = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-business"],
                "channel": "web",
                "segmentRefs": ["seg-business"],
                "productCode": "rail-business",
            },
            headers={"Idempotency-Key": uuid7_key(912)},
        )
        self.assertEqual(business_quote.status_code, 201, business_quote.text)
        self.assertEqual(business_quote.json()["breakdown"]["total"], {"currency": "CNY", "minorUnits": 18000})
        self.assertEqual(business_quote.json()["ruleSnapshot"]["ruleSetId"], business["ruleSetId"])

        refund_resp = self.client.post(
            "/api/v1/adjustment-quotes",
            json={
                "purpose": "REFUND",
                "entitlementIds": ["ent-business"],
                "journeyOrderId": "ord-business",
                "segmentRefs": ["seg-business"],
            },
            headers={"Idempotency-Key": uuid7_key(913)},
        )
        self.assertEqual(refund_resp.status_code, 201, refund_resp.text)
        self.assertEqual(refund_resp.json()["refundableAmount"], {"currency": "CNY", "minorUnits": 14000})

    def test_publish_rule_set_is_idempotent(self) -> None:
        created = self.create_rule_set(version="2026.07.05")
        key = uuid7_key(904)
        first = self.client.post(f"/api/v1/fare-rule-sets/{created['ruleSetId']}/publish", json={}, headers={"Idempotency-Key": key})
        second = self.client.post(f"/api/v1/fare-rule-sets/{created['ruleSetId']}/publish", json={}, headers={"Idempotency-Key": key})
        self.assertEqual(first.status_code, 200, first.text)
        self.assertEqual(second.status_code, 200, second.text)
        self.assertEqual(first.json(), second.json())
        self.assertEqual([event.event_type for event in self.publisher.published_events], ["FareRuleSetPublished", "FareRuleSetSuperseded"])
        self.assertEqual(
            self.publisher.published_events[0].event_id,
            deterministic_rule_set_event_id(created["ruleSetId"], "2026.07.05", "published"),
        )
        self.assertEqual(
            self.publisher.published_events[1].event_id,
            deterministic_rule_set_event_id(self.rs.rule_set_id, self.rs.version, "superseded"),
        )

    def test_publish_event_failure_rolls_back_state_and_retry_uses_same_event_id(self) -> None:
        created = self.create_rule_set(version="2026.07.rollback")
        original = self.store.fare_rule_sets[self.rs.rule_set_id]
        self.publisher.fail_next_publish()

        failed = self.client.post(
            f"/api/v1/fare-rule-sets/{created['ruleSetId']}/publish",
            json={},
            headers={"Idempotency-Key": uuid7_key(914)},
        )

        self.assertEqual(failed.status_code, 503, failed.text)
        self.assertEqual(failed.json()["code"], "UNAVAILABLE")
        self.assertEqual(self.store.fare_rule_sets[created["ruleSetId"]].status, RuleSetStatus.DRAFT)
        self.assertEqual(self.store.fare_rule_sets[self.rs.rule_set_id], original)
        self.assertEqual(self.publisher.published_event_count, 0)

        retry = self.client.post(
            f"/api/v1/fare-rule-sets/{created['ruleSetId']}/publish",
            json={},
            headers={"Idempotency-Key": uuid7_key(914)},
        )
        self.assertEqual(retry.status_code, 200, retry.text)
        first_event_ids = [event.event_id for event in self.publisher.published_events]
        self.assertEqual(len(first_event_ids), 2)
        self.assertTrue(all(event_id.startswith("evt-") for event_id in first_event_ids))
        self.assertEqual(uuid.UUID(first_event_ids[0].removeprefix("evt-")).version, 7)

        replay = self.client.post(
            f"/api/v1/fare-rule-sets/{created['ruleSetId']}/publish",
            json={},
            headers={"Idempotency-Key": uuid7_key(915)},
        )
        self.assertEqual(replay.status_code, 200, replay.text)
        self.assertEqual([event.event_id for event in self.publisher.published_events], first_event_ids)

    def test_superseded_event_failure_rolls_back_state_and_retry_uses_same_event_ids(self) -> None:
        created = self.create_rule_set(version="2026.07.supersede-failure")
        original = self.store.fare_rule_sets[self.rs.rule_set_id]
        self.publisher.fail_on_publish_number(2)

        failed = self.client.post(
            f"/api/v1/fare-rule-sets/{created['ruleSetId']}/publish",
            json={},
            headers={"Idempotency-Key": uuid7_key(916)},
        )

        self.assertEqual(failed.status_code, 503, failed.text)
        self.assertEqual(self.store.fare_rule_sets[created["ruleSetId"]].status, RuleSetStatus.DRAFT)
        self.assertEqual(self.store.fare_rule_sets[self.rs.rule_set_id], original)
        first_published_event_id = self.publisher.published_events[0].event_id
        expected_published_event_id = deterministic_rule_set_event_id(
            created["ruleSetId"], "2026.07.supersede-failure", "published"
        )
        expected_superseded_event_id = deterministic_rule_set_event_id(self.rs.rule_set_id, self.rs.version, "superseded")
        self.assertEqual(first_published_event_id, expected_published_event_id)

        retry = self.client.post(
            f"/api/v1/fare-rule-sets/{created['ruleSetId']}/publish",
            json={},
            headers={"Idempotency-Key": uuid7_key(916)},
        )
        self.assertEqual(retry.status_code, 200, retry.text)
        event_ids = [event.event_id for event in self.publisher.published_events]
        self.assertEqual(event_ids[0], event_ids[1])
        self.assertEqual(event_ids[0], first_published_event_id)
        self.assertNotEqual(event_ids[1], event_ids[2])
        self.assertEqual(event_ids[2], expected_superseded_event_id)
        self.assertEqual(uuid.UUID(event_ids[2].removeprefix("evt-")).version, 7)
        self.assertEqual([event.event_type for event in self.publisher.published_events], [
            "FareRuleSetPublished",
            "FareRuleSetPublished",
            "FareRuleSetSuperseded",
        ])

    def test_fare_quote_happy_path(self) -> None:
        """POST /api/v1/fare-quotes returns 201 with quote details."""
        resp = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": uuid7_key()},
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
        self.assertEqual(self.publisher.published_event_count, 1)
        event = self.publisher.last_event()
        assert event is not None
        self.assertEqual(event.event_type, "FareQuoteComputed")
        self.assertTrue(event.event_id.startswith("evt-"))
        self.assertTrue(event.causation_id.startswith("cmd-"))
        self.assertEqual(event.payload["quoteId"], data["quoteId"])

    def test_fare_quote_validation_failure(self) -> None:
        """Invalid requests produce 400 with canonical error body."""
        resp = self.client.post(
            "/api/v1/fare-quotes",
            json={"travelerRefs": [], "channel": "web", "segmentRefs": []},
            headers={"Idempotency-Key": uuid7_key()},
        )
        self.assertEqual(resp.status_code, 400)
        data = resp.json()
        self.assertEqual(data["code"], "VALIDATION_FAILED")
        self.assertIn("correlationId", data)
        for error in data["details"]["errors"]:
            self.assertEqual(set(error), {"loc", "type", "msg"})
            self.assertNotIn("input", error)

    def test_fare_quote_requires_idempotency_key(self) -> None:
        resp = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
        )
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["code"], "VALIDATION_FAILED")

    def test_adjustment_quote_requires_idempotency_key(self) -> None:
        resp = self.client.post(
            "/api/v1/adjustment-quotes",
            json={
                "purpose": "REFUND",
                "entitlementIds": ["ent-456"],
                "journeyOrderId": "ord-456",
                "segmentRefs": ["seg-456"],
            },
        )
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["code"], "VALIDATION_FAILED")

    def test_fare_quote_rejects_non_uuid7_idempotency_key(self) -> None:
        for bad_key in ["not-a-uuid-v7", str(uuid4())]:
            resp = self.client.post(
                "/api/v1/fare-quotes",
                json={
                    "travelerRefs": ["tvl-123"],
                    "channel": "web",
                    "segmentRefs": ["seg-456"],
                },
                headers={"Idempotency-Key": bad_key},
            )
            self.assertEqual(resp.status_code, 400)
            self.assertEqual(resp.json()["code"], "VALIDATION_FAILED")

    def test_fare_quote_accepts_uuid7_idempotency_key(self) -> None:
        resp = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": uuid7_key(999)},
        )
        self.assertEqual(resp.status_code, 201)

    def test_adjustment_quote_rejects_non_uuid7_idempotency_key(self) -> None:
        for bad_key in ["not-a-uuid-v7", str(uuid4())]:
            resp = self.client.post(
                "/api/v1/adjustment-quotes",
                json={
                    "purpose": "REFUND",
                    "entitlementIds": ["ent-456"],
                    "journeyOrderId": "ord-456",
                    "segmentRefs": ["seg-456"],
                },
                headers={"Idempotency-Key": bad_key},
            )
            self.assertEqual(resp.status_code, 400)
            self.assertEqual(resp.json()["code"], "VALIDATION_FAILED")

    def test_adjustment_quote_accepts_uuid7_idempotency_key(self) -> None:
        create_resp = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": uuid7_key(997)},
        )
        self.assertEqual(create_resp.status_code, 201)
        self.store.fare_rule_sets[self.rs.rule_set_id] = published_rule_set(
            rule("refund", RuleKind.REFUND_FEE, "20.00"),
        )
        resp = self.client.post(
            "/api/v1/adjustment-quotes",
            json={
                "purpose": "REFUND",
                "entitlementIds": ["ent-456"],
                "journeyOrderId": "ord-456",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": uuid7_key(998)},
        )
        self.assertEqual(resp.status_code, 201)

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
            headers={"Idempotency-Key": uuid7_key()},
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
            headers={"Idempotency-Key": uuid7_key()},
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
            headers={"Idempotency-Key": uuid7_key()},
        )
        self.assertEqual(create_resp.status_code, 201)
        quote_id = create_resp.json()["quoteId"]

        # Add refund fee rule; request segmentRefs resolve to the quoted segment seg-456.
        self.store.fare_rule_sets[self.rs.rule_set_id] = published_rule_set(
            rule("refund", RuleKind.REFUND_FEE, "20.00"),
        )
        resp = self.client.post(
            "/api/v1/adjustment-quotes",
            json={
                "purpose": "REFUND",
                "entitlementIds": ["ent-456"],
                "journeyOrderId": "ord-456",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": uuid7_key()},
        )
        self.assertEqual(resp.status_code, 201)
        data = resp.json()
        self.assertIn("adjustmentQuoteId", data)
        self.assertEqual(data["purpose"], "REFUND")
        self.assertIn("status", data)
        self.assertIn("refundableAmount", data)
        self.assertIn("amountDue", data)
        adjustment_events = [e for e in self.publisher.published_events if e.event_type == "AdjustmentQuoteComputed"]
        self.assertEqual(len(adjustment_events), 1)
        self.assertEqual(adjustment_events[0].payload["adjustmentQuoteId"], data["adjustmentQuoteId"])
        self.assertEqual(adjustment_events[0].payload["originalQuoteId"], quote_id)
        self.assertEqual(adjustment_events[0].payload["entitlementIds"], ["ent-456"])
        self.assertEqual(adjustment_events[0].payload["segmentRefs"], ["seg-456"])

    def test_adjustment_quote_not_found(self) -> None:
        """No segment-matching original quote fails the precondition."""
        resp = self.client.post(
            "/api/v1/adjustment-quotes",
            json={
                "purpose": "REFUND",
                "entitlementIds": ["ent-456"],
                "journeyOrderId": "ord-456",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": uuid7_key()},
        )
        self.assertEqual(resp.status_code, 412)
        self.assertEqual(resp.json()["code"], "PRECONDITION_FAILED")

    def test_adjustment_quote_unknown_fields_are_rejected(self) -> None:
        resp = self.client.post(
            "/api/v1/adjustment-quotes",
            json={
                "purpose": "REFUND",
                "entitlementIds": ["ent-456"],
                "journeyOrderId": "ord-456",
                "segmentRefs": ["seg-456"],
                "fareQuoteRef": "fq-contract-forbidden",
            },
            headers={"Idempotency-Key": uuid7_key()},
        )
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["code"], "VALIDATION_FAILED")

    def test_idempotent_replay_returns_original_result(self) -> None:
        """Same Idempotency-Key returns the original response."""
        idempotency_key = uuid7_key()

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
        self.assertEqual(self.publisher.published_event_count, 1)

    def test_idempotency_key_reused_with_different_body(self) -> None:
        """Different body with same Idempotency-Key returns 422."""
        idempotency_key = uuid7_key()

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
        corr_id = f"corr-{uuid7()}"
        resp = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
            headers={
                "Idempotency-Key": uuid7_key(),
                "X-Correlation-Id": corr_id,
            },
        )
        self.assertEqual(resp.status_code, 201)
        self.assertEqual(resp.headers.get("X-Correlation-Id"), corr_id)

    def test_generated_http_and_event_ids_are_uuid7(self) -> None:
        resp = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": uuid7_key(996)},
        )
        self.assertEqual(resp.status_code, 201)
        request_id = resp.headers["X-Request-ID"]
        correlation_id = resp.headers["X-Correlation-Id"]
        event = self.publisher.last_event()
        assert event is not None
        self.assertEqual(uuid.UUID(request_id).version, 7)
        self.assertEqual(uuid.UUID(correlation_id.removeprefix("corr-")).version, 7)
        self.assertEqual(uuid.UUID(event.event_id.removeprefix("evt-")).version, 7)
        self.assertEqual(uuid.UUID(event.causation_id.removeprefix("cmd-")).version, 7)

    def test_money_format_minor_units(self) -> None:
        """Money amounts use minorUnits integer, not float."""
        resp = self.client.post(
            "/api/v1/fare-quotes",
            json={
                "travelerRefs": ["tvl-123"],
                "channel": "web",
                "segmentRefs": ["seg-456"],
            },
            headers={"Idempotency-Key": uuid7_key()},
        )
        data = resp.json()
        total = data["breakdown"]["total"]
        self.assertIsInstance(total["minorUnits"], int)
        self.assertEqual(total["currency"], "CNY")


class FarePricingMessagingTest(unittest.TestCase):
    """Messaging port tests — no live Redis required (in-memory fake)."""

    def test_fare_pricing_is_producer_only(self) -> None:
        from fare_pricing import main

        self.assertFalse(hasattr(main.app.state, "event_subscriber"))

    def test_event_publisher_wraps_in_envelope(self) -> None:
        """FakeEventPublisher stores events with correct envelope fields."""
        publisher = FakeEventPublisher()
        envelope = EventEnvelope(
            event_id="evt-test-123",
            event_type="FareRuleSetPublished",
            schema_version=1,
            producer="fare-pricing",
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
        self.assertEqual(d["causationId"], "cmd-test-789")
        self.assertEqual(d["correlationId"], "corr-test-000")
        self.assertIn("occurredAt", d)
        self.assertIn("payload", d)
        self.assertEqual(d["payload"]["ruleSetId"], "rs-main")
        self.assertEqual(set(d.keys()), {"eventId", "eventType", "occurredAt", "correlationId", "causationId", "producer", "schemaVersion", "payload"})

        without_cause = EventEnvelope(
            event_id="evt-test-no-cause",
            event_type="FareRuleSetPublished",
            schema_version=1,
            producer="fare-pricing",
            correlation_id="corr-test-000",
            occurred_at=NOW,
            payload={"ruleSetId": "rs-main"},
        ).to_json_dict()
        self.assertNotIn("causationId", without_cause)

    def test_publisher_roundtrip_json(self) -> None:
        """Envelope survives to_json_dict -> from_json_dict roundtrip."""
        envelope = EventEnvelope(
            event_id="evt-test-rt",
            event_type="FareRuleSetPublished",
            schema_version=1,
            producer="fare-pricing",
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


    def test_envelope_from_json_validates_contract_shape(self) -> None:
        with self.assertRaises(ValueError):
            EventEnvelope.from_json_dict({})

        with self.assertRaises(ValueError):
            EventEnvelope.from_json_dict({
                "eventId": "evt-test-extra",
                "eventType": "FareRuleSetPublished",
                "occurredAt": "2026-07-03T12:00:00.000Z",
                "correlationId": "corr-test",
                "producer": "fare-pricing",
                "schemaVersion": 1,
                "payload": {},
                "legacyCommandId": "cmd-legacy",
            })

        restored = EventEnvelope.from_json_dict({
            "eventId": "evt-test-minimal",
            "eventType": "FareRuleSetPublished",
            "occurredAt": "2026-07-03T12:00:00.000Z",
            "correlationId": "corr-test",
            "producer": "fare-pricing",
            "schemaVersion": 1,
            "payload": {},
        })
        self.assertEqual(restored.event_id, "evt-test-minimal")
        self.assertEqual(restored.causation_id, "")

    def test_envelope_wire_format_matches_contract_example(self) -> None:
        """Verify the envelope JSON matches the contract's example structure."""
        envelope = EventEnvelope(
            event_id="evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222",
            event_type="FareRuleSetPublished",
            schema_version=1,
            producer="fare-pricing",
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

    def test_fake_subscriber_deduplicates_duplicate_event_ids(self) -> None:
        duplicate = EventEnvelope(
            event_id="evt-duplicate",
            event_type="FareRuleSetPublished",
            producer="fare-pricing",
            correlation_id="corr-test",
            occurred_at=NOW,
            payload={"ruleSetId": "rs-main"},
        )
        subscriber = FakeEventSubscriber([duplicate, duplicate])
        handled: list[str] = []

        subscriber.subscribe(
            ["events:fare-pricing"],
            "fare-pricing",
            "fare-pricing-test",
            lambda envelope: handled.append(envelope.event_id),
        )

        self.assertEqual(handled, ["evt-duplicate"])


if __name__ == "__main__":
    unittest.main()


def test_ready_returns_503_when_readiness_gate_is_false() -> None:
    from train_ticket_platform.storage import ReadinessGate

    app = create_app(store=InMemoryStore(), event_publisher=FakeEventPublisher())
    gate = ReadinessGate()
    gate.mark_failed("migration failed")
    app.state.readiness = gate
    client = TestClient(app)

    assert client.get("/ready").status_code == 503
    assert client.get("/readyz").status_code == 503
