from __future__ import annotations

import importlib.util
from datetime import UTC, datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "reporting_backfill_revenue_dimensions",
    ROOT / "scripts/backfill_revenue_dimensions.py",
)
backfill = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(backfill)
NOW = datetime(2026, 9, 25, 4, 0, tzinfo=UTC)


def test_capture_uses_event_identity_and_snapshot_reference_fallback():
    row = backfill.capture_event_row(
        {"eventId": "evt-cap", "eventType": "PaymentCaptured", "occurredAt": "2026-09-25T04:01:00Z", "payload": {}},
        NOW,
        "ord-1",
        "pi-1",
        "purchase",
    )
    assert row == ("evt-cap", "ord-1", "pi-1", "purchase", datetime(2026, 9, 25, 4, 1, tzinfo=UTC))


def test_order_snapshot_extracts_route_dates_and_traveler_types():
    routes, travelers, scope = backfill.order_rows({
        "orderId": "ord-1",
        "segments": [
            {"segmentRef": "seg-b", "departureTime": "2026-10-02T09:00:00Z"},
            {"segmentRef": "seg-a", "departureTime": "2026-10-01T09:00:00Z"},
        ],
        "travelers": [
            {"travelerId": "tvl-2", "travelerType": "CHILD"},
            {"travelerId": "tvl-1", "travelerType": "ADULT"},
        ],
    }, NOW)
    assert routes == [
        ("ord-1", "seg-b", "2026-10-02", NOW),
        ("ord-1", "seg-a", "2026-10-01", NOW),
    ]
    assert travelers == [
        ("ord-1", "tvl-2", "CHILD", NOW),
        ("ord-1", "tvl-1", "ADULT", NOW),
    ]
    assert scope == ("ord-1", "ord-1", [], [], NOW)


def test_post_sales_and_attachment_snapshots_keep_authoritative_scope():
    assert backfill.post_sales_scope_row({
        "caseId": "case-raw",
        "journeyOrderId": "ord-1",
        "scope": {"segmentRefs": ["seg-b"], "travelerRefs": ["tvl-2"]},
    }, NOW) == ("psc-case-raw", "ord-1", ["seg-b"], ["tvl-2"], NOW)
    scope, attachment = backfill.ancillary_rows({
        "ancillaryOrderItemId": "aoi-1", "journeyOrderId": "ord-1",
        "segmentRef": "seg-a", "travelerRef": "tvl-1",
        "catalogSnapshot": {"serviceType": "INSURANCE"},
    }, NOW)
    assert scope == ("aoi-1", "ord-1", ["seg-a"], ["tvl-1"], NOW)
    assert attachment is None

    _, confirmed_attachment = backfill.ancillary_rows({
        "ancillaryOrderItemId": "aoi-1", "journeyOrderId": "ord-1",
        "segmentRef": "seg-a", "travelerRef": "tvl-1",
        "serviceType": "INSURANCE", "status": "CONFIRMED", "paymentIntentId": "pi-1",
    }, NOW)
    assert confirmed_attachment == ("ord-1", "INSURANCE", NOW)


def test_seat_snapshot_requires_complete_authoritative_key():
    assert backfill.seat_row({
        "journeyOrderId": "ord-1", "segmentRef": "seg-a", "travelerRef": "tvl-1", "classRef": "SECOND_CLASS",
    }, NOW, 1) == ("ord-1", "seg-a", "tvl-1", "SECOND_CLASS", 1, NOW)
    assert backfill.seat_row({"journeyOrderId": "ord-1"}, NOW, 1) is None
