from __future__ import annotations

from typing import Any, Mapping

from fastapi.testclient import TestClient
from train_ticket_platform.messaging import InMemoryEventPublisher

from legacy_acl.api import create_app
from legacy_acl.downstream import DownstreamError
from legacy_acl.ids import deterministic_event_id
from train_ticket_platform.ids import is_uuid7


KEY = "0194f2e0-7b3e-7610-8000-000000000111"
HEADERS = {"Idempotency-Key": KEY, "X-Legacy-Operator": "op-1", "X-Correlation-Id": "corr-0194f2e0-7b3e-7610-8000-000000000999"}


class FakeDownstream:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, str, Mapping[str, Any], Mapping[str, str]]] = []
        self.fail_on: tuple[str, str] | None = None

    def post(self, service: str, path: str, body: Mapping[str, Any], headers: Mapping[str, str] | None = None) -> dict[str, Any]:
        self.calls.append(("POST", service, path, dict(body), dict(headers or {})))
        if self.fail_on == (service, path):
            raise DownstreamError("downstream rejected")
        if service == "trip-planning":
            return {"itineraries": [{"itineraryRef": "itin_1", "legs": [{"serviceSegmentRef": "seg-1"}]}]}
        if service == "fare-pricing" and path == "/api/v1/fare-quotes":
            return {"quoteId": "fq-1", "breakdown": {"total": {"currency": "CNY", "minorUnits": 10750}}}
        if service == "offer-management":
            return {"offerId": "off-1", "offerVersion": 1, "total": {"currency": "CNY", "minorUnits": 10750}}
        if service == "journey-order" and path == "/api/v1/journey-orders":
            return {"orderId": "ord-1", "accountId": "acc-1", "travelerRefs": ["tvl-1"], "segmentRefs": ["seg-1"]}
        if service == "booking-orchestration" and path == "/api/v1/internal/booking-sagas":
            return {"sagaId": "saga-1"}
        if service == "booking-orchestration" and path.endswith("/request-reservation"):
            return {"segmentBookingId": body["segmentBookingId"], "status": "REQUESTED"}
        if service == "payment" and path == "/api/v1/payment-intents":
            return {"paymentIntentId": "pi-1"}
        if service == "payment" and path.endswith("/capture"):
            return {"paymentIntentId": "pi-1", "status": "CAPTURED"}
        if service == "entitlement-ticketing" and path == "/api/v1/entitlements":
            return {"entitlementId": "ent-1", "segmentBookingId": body["segmentBookingId"]}
        if service == "fulfillment":
            return {"fulfillmentRecordId": "fr-1"}
        if service == "post-sales" and path == "/api/v1/post-sales-cases":
            return {"caseId": "psc-1"}
        if service == "post-sales" and path.endswith("/evaluate"):
            return {"caseId": "psc-1", "refundableAmount": {"currency": "CNY", "minorUnits": 8750}, "amountDue": {"currency": "CNY", "minorUnits": 0}}
        if service == "post-sales" and path.endswith("/approve"):
            return {"caseId": "psc-1", "status": "APPROVED"}
        raise AssertionError((service, path, body))

    def get(self, service: str, path: str, headers: Mapping[str, str] | None = None) -> dict[str, Any]:
        self.calls.append(("GET", service, path, {}, dict(headers or {})))
        if service == "journey-order":
            return {"orderId": "ord-1", "accountId": "acc-1", "travelerRefs": ["tvl-1"], "segmentRefs": ["seg-1"]}
        if service == "entitlement-ticketing":
            return {"items": [{"entitlementId": "ent-1", "segmentBookingId": "sb-1", "journeyOrderId": "ord-1", "travelerRef": "tvl-1", "segmentRef": "seg-1"}], "total": 1, "limit": 20, "offset": 0}
        raise AssertionError((service, path))


def client(fake: FakeDownstream, publisher: InMemoryEventPublisher) -> TestClient:
    return TestClient(create_app(client=fake, event_publisher=publisher))


def test_preserve_maps_full_chain_and_publishes_contract_event() -> None:
    fake = FakeDownstream()
    publisher = InMemoryEventPublisher()
    response = client(fake, publisher).post(
        "/api/v1/legacy/preserve",
        headers=HEADERS,
        json={"accountId": "acc-1", "contactsId": "tvl-1", "tripId": "G1", "seatType": "SECOND", "date": "2026-08-01", "from": "p-bj", "to": "p-sh"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == 1
    assert body["msg"] == "success"
    assert body["data"]["orderId"] == "ord-1"
    assert [call[1] for call in fake.calls[:6]] == ["trip-planning", "fare-pricing", "offer-management", "journey-order", "booking-orchestration", "booking-orchestration"]
    envelope = publisher.envelopes[-1]
    assert envelope.eventId == deterministic_event_id(KEY, "PRESERVE")
    assert envelope.producer == "legacy-acl"
    assert envelope.eventType == "LegacyCommandMapped"
    assert envelope.payload["legacyOperation"] == "PRESERVE"
    assert envelope.payload["outcome"] == "SUCCEEDED"
    assert envelope.payload["operatorRef"] == "op-1"
    assert envelope.payload["sourceRef"] == KEY
    assert envelope.payload["resultRefs"]["offerId"] == "off-1"
    assert envelope.payload["metadata"]["sourceCommandId"] == KEY


def test_failure_returns_legacy_shape_and_publishes_failed_event() -> None:
    fake = FakeDownstream()
    fake.fail_on = ("trip-planning", "/api/v1/itineraries/search")
    publisher = InMemoryEventPublisher()
    response = client(fake, publisher).post(
        "/api/v1/legacy/preserve",
        headers=HEADERS,
        json={"accountId": "acc-1", "contactsId": "tvl-1", "date": "2026-08-01", "from": "p-bj", "to": "p-sh"},
    )

    assert response.status_code == 200
    assert response.json() == {"status": 0, "msg": "downstream rejected", "data": {}}
    envelope = publisher.envelopes[-1]
    assert envelope.eventId == deterministic_event_id(KEY, "PRESERVE")
    assert envelope.payload["outcome"] == "FAILED"
    assert envelope.payload["failureMessage"] == "downstream rejected"
    assert envelope.payload["resultRefs"] == {}


def test_inside_payment_ticket_issue_execute_cancel_and_rebook_mapping() -> None:
    endpoints = [
        ("/api/v1/legacy/inside_payment", {"orderId": "ord-1", "price": {"currency": "CNY", "minorUnits": 10750}}, "INSIDE_PAYMENT", "paymentIntentId"),
        ("/api/v1/legacy/ticket_issue", {"orderId": "ord-1"}, "TICKET_ISSUE", "entitlementId"),
        ("/api/v1/legacy/execute", {"orderId": "ord-1"}, "EXECUTE", "fulfillmentRecordId"),
        ("/api/v1/legacy/cancel", {"orderId": "ord-1"}, "CANCEL", "refundAmount"),
        ("/api/v1/legacy/rebook", {"orderId": "ord-1", "date": "2026-08-02", "seatType": "FIRST"}, "REBOOK", "amountDue"),
    ]
    for index, (path, payload, operation, result_field) in enumerate(endpoints):
        fake = FakeDownstream()
        publisher = InMemoryEventPublisher()
        headers = {**HEADERS, "Idempotency-Key": f"0194f2e0-7b3e-7610-8000-0000000002{index:02d}"}
        response = client(fake, publisher).post(path, headers=headers, json=payload)
        assert response.status_code == 200
        body = response.json()
        assert body["status"] == 1
        assert result_field in body["data"]
        if operation == "CANCEL":
            assert body["data"]["refundAmount"] == {"currency": "CNY", "minorUnits": 8750}
        assert publisher.envelopes[-1].payload["legacyOperation"] == operation
        assert publisher.envelopes[-1].payload["outcome"] == "SUCCEEDED"


def test_downstream_post_idempotency_keys_are_distinct_uuid7_and_stable_on_replay() -> None:
    payload = {"accountId": "acc-1", "contactsId": "tvl-1", "tripId": "G1", "seatType": "SECOND", "date": "2026-08-01", "from": "p-bj", "to": "p-sh"}

    fake_first = FakeDownstream()
    first_response = client(fake_first, InMemoryEventPublisher()).post("/api/v1/legacy/preserve", headers=HEADERS, json=payload)
    assert first_response.status_code == 200
    first_post_keys = [call[4]["Idempotency-Key"] for call in fake_first.calls if call[0] == "POST"]

    fake_replay = FakeDownstream()
    replay_response = client(fake_replay, InMemoryEventPublisher()).post("/api/v1/legacy/preserve", headers=HEADERS, json=payload)
    assert replay_response.status_code == 200
    replay_post_keys = [call[4]["Idempotency-Key"] for call in fake_replay.calls if call[0] == "POST"]

    assert first_post_keys == replay_post_keys
    assert len(first_post_keys) == len(set(first_post_keys))
    assert all(is_uuid7(key) for key in first_post_keys)
    assert HEADERS["Idempotency-Key"] not in first_post_keys


def test_operator_header_is_required() -> None:
    fake = FakeDownstream()
    publisher = InMemoryEventPublisher()
    response = client(fake, publisher).post("/api/v1/legacy/execute", headers={"Idempotency-Key": KEY}, json={"orderId": "ord-1"})

    assert response.status_code == 400
    assert response.json()["status"] == 0
    assert publisher.envelopes == []
