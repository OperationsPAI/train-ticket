from __future__ import annotations

import os
import importlib.util
from decimal import Decimal
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg import sql
from psycopg.conninfo import make_conninfo
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

from reporting.adapters.storage.postgres import PostgresReportingApplicationService
from reporting.application.ports import EventEnvelope
from reporting.domain import Money


ROOT = Path(__file__).resolve().parents[1]
VIEW_MIGRATION = ROOT / "migrations/008_authoritative_revenue_views.sql"
BACKFILL_SPEC = importlib.util.spec_from_file_location(
    "reporting_backfill_live",
    ROOT / "scripts/backfill_revenue_dimensions.py",
)
backfill_module = importlib.util.module_from_spec(BACKFILL_SPEC)
assert BACKFILL_SPEC.loader is not None
BACKFILL_SPEC.loader.exec_module(backfill_module)
MIGRATE_SPEC = importlib.util.spec_from_file_location(
    "reporting_migrate_authoritative_revenue_live",
    ROOT / "scripts/migrate_authoritative_revenue.py",
)
migrate_module = importlib.util.module_from_spec(MIGRATE_SPEC)
assert MIGRATE_SPEC.loader is not None
MIGRATE_SPEC.loader.exec_module(migrate_module)


@pytest.fixture
def authoritative_database():
    url = os.environ.get("REPORTING_TEST_DATABASE_URL")
    if not url:
        pytest.skip("REPORTING_TEST_DATABASE_URL must identify a local disposable PostgreSQL")
    schema = "reporting_authoritative_" + uuid4().hex
    with psycopg.connect(url, autocommit=True) as admin:
        admin.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
        try:
            with ConnectionPool(url, min_size=1, max_size=4, kwargs={"options": f"-c search_path={schema} -c statement_timeout=30000 -c lock_timeout=2000"}) as pool:
                with pool.connection() as conn:
                    for migration in sorted((ROOT / "migrations").glob("*.sql")):
                        if migration == VIEW_MIGRATION:
                            continue
                        conn.execute(migration.read_text())
                yield pool
        finally:
            admin.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))


def event(event_id: str, event_type: str, producer: str, payload: dict) -> EventEnvelope:
    return EventEnvelope(
        eventId=event_id,
        eventType=event_type,
        occurredAt="2026-09-25T04:00:00.000Z",
        correlationId="corr-authoritative",
        producer=producer,
        schemaVersion=1,
        payload=payload,
        causationId="evt-source",
    )


def apply_view_migration(conn) -> None:
    conn.execute("SET LOCAL reporting.allow_revenue_rebuild='on'")
    conn.execute(VIEW_MIGRATION.read_text())


def test_authoritative_facts_gate_and_attribute_revenue(authoritative_database):
    service = PostgresReportingApplicationService(authoritative_database)
    capture = event("evt-capture", "PaymentCaptured", "payment", {
        "businessRef": "ord-1",
        "paymentIntentId": "pi-1",
        "purpose": "purchase",
        "capturedAmount": {"currency": "CNY", "minorUnits": 1001},
        "routeId": "forged-route",
        "seatClass": "forged-class",
        "travelerType": "forged-type",
        "ancillaryAttached": True,
        "insuranceAttached": True,
    })
    assert service.handle_event(capture).status.value == "SUCCESS"
    with authoritative_database.connection() as conn:
        assert conn.execute("SELECT route_id,seat_class,passenger_type FROM reporting_metric_events WHERE event_id='evt-capture'").fetchone() == (None, None, None)
        with pytest.raises(psycopg.errors.RaiseException, match="completed authoritative-revenue-v1 backfill"):
            with conn.transaction():
                apply_view_migration(conn)
        conn.execute(
            "INSERT INTO reporting_dimension_backfills(backfill_name,completed_at,details) VALUES ('authoritative-revenue-v1',now(),'{}')"
        )
        conn.execute(
            "INSERT INTO reporting_metric_events(event_id,event_type,occurred_at,amount,currency,flags) VALUES ('evt-orphan','PaymentCaptured',now(),1,'CNY','{}')"
        )
        with pytest.raises(psycopg.errors.RaiseException, match="reference for every captured payment"):
            with conn.transaction():
                apply_view_migration(conn)
        conn.execute(
            "INSERT INTO reporting_capture_references(event_id,business_ref,source_occurred_at) VALUES ('evt-orphan','unresolved:evt-orphan',now())"
        )

    facts = [
        event("evt-order", "JourneyOrderCreated", "journey-order", {
            "orderId": "ord-1",
            "segments": [
                {"segmentRef": "seg-b", "departureTime": "2026-10-02T09:00:00Z"},
                {"segmentRef": "seg-a", "departureTime": "2026-10-01T09:00:00Z"},
            ],
            "travelerRefs": [
                {"travelerId": "tvl-1", "travelerType": "ADULT"},
                {"travelerId": "tvl-2", "travelerType": "ADULT"},
                {"travelerId": "tvl-3", "travelerType": "CHILD"},
            ],
        }),
        event("evt-seat-1", "SegmentReservationRequested", "booking-orchestration", {"journeyOrderId": "ord-1", "segmentRef": "seg-a", "travelerRef": "tvl-1", "classRef": "SECOND_CLASS"}),
        event("evt-seat-2", "SegmentReservationRequested", "booking-orchestration", {"journeyOrderId": "ord-1", "segmentRef": "seg-a", "travelerRef": "tvl-2", "classRef": "SECOND_CLASS"}),
        event("evt-seat-3", "SegmentReservationRequested", "booking-orchestration", {"journeyOrderId": "ord-1", "segmentRef": "seg-a", "travelerRef": "tvl-3", "classRef": "FIRST_CLASS"}),
        event("evt-anc", "AncillaryOrderItemConfirmed", "ancillary-service", {"ancillaryOrderItemId": "aoi-1", "journeyOrderId": "ord-1", "segmentRef": "seg-a", "travelerRef": "tvl-1", "serviceType": "MEAL", "paymentIntentId": "pi-aoi-1"}),
        event("evt-ins", "InsurancePolicyIssued", "travel-insurance", {"policyId": "pol-1", "journeyOrderId": "ord-1", "segmentRefs": ["seg-b"], "travelerRef": "tvl-2"}),
    ]
    for fact in facts:
        assert service.handle_event(fact).status.value == "SUCCESS"

    with authoritative_database.connection() as conn:
        apply_view_migration(conn)
        rows = conn.execute(
            "SELECT dimension,dimension_value,revenue,payment_count,ancillary_count,insurance_count FROM reporting_revenue_breakdowns ORDER BY dimension,dimension_value"
        ).fetchall()
    by_dimension = {(row[0], row[1]): row[2:] for row in rows}
    assert by_dimension[("route", "seg-a")] == (Decimal("5.01"), 1, 1, 1)
    assert by_dimension[("route", "seg-b")] == (Decimal("5.00"), 1, 1, 1)
    assert by_dimension[("traveler_type", "ADULT")] == (Decimal("6.68"), 1, 1, 1)
    assert by_dimension[("traveler_type", "CHILD")] == (Decimal("3.33"), 1, 1, 1)
    assert by_dimension[("seat_class", "SECOND_CLASS")] == (Decimal("6.67"), 1, 1, 1)
    assert by_dimension[("seat_class", "FIRST_CLASS")] == (Decimal("3.34"), 1, 1, 1)
    for dimension in ("route", "traveler_type", "seat_class", "channel", "time_period"):
        total = sum((row[2] for row in rows if row[0] == dimension), Decimal("0.00"))
        assert total == Decimal("11.01")

    report = service.revenue_report("traveler_type")
    assert report.total_revenue == (Money("11.01", "CNY"),)
    assert {item.value for item in report.items} == {"ADULT", "CHILD", "unknown"}


def test_authoritative_views_install_on_empty_database(authoritative_database):
    with authoritative_database.connection() as conn:
        apply_view_migration(conn)
        assert conn.execute("SELECT count(*) FROM reporting_revenue_breakdowns").fetchone()[0] == 0


def test_historical_backfill_is_batched_idempotent_and_switchable():
    url = os.environ.get("REPORTING_TEST_DATABASE_URL")
    if not url:
        pytest.skip("REPORTING_TEST_DATABASE_URL must identify a local disposable PostgreSQL")
    reporting_schema = "reporting_backfill_target_" + uuid4().hex
    source_schema = "reporting_backfill_source_" + uuid4().hex
    with psycopg.connect(url, autocommit=True) as admin:
        admin.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(reporting_schema)))
        admin.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(source_schema)))
        try:
            reporting_url = make_conninfo(url, options=f"-c search_path={reporting_schema}")
            source_url = make_conninfo(url, options=f"-c search_path={source_schema}")
            with psycopg.connect(reporting_url) as target:
                for migration in sorted((ROOT / "migrations").glob("*.sql")):
                    if migration.name > "006_exclude_incomplete_captures.sql":
                        break
                    target.execute(migration.read_text())
                with target.cursor() as cursor:
                    cursor.executemany(
                        """
                        INSERT INTO reporting_metric_events(event_id,event_type,occurred_at,amount,currency,channel,flags)
                        VALUES (%s,'PaymentCaptured','2026-09-25T04:00:00Z',%s,'CNY','ALIPAY',%s)
                        """,
                        [
                            ("evt-purchase", Decimal("10.01"), Jsonb({})),
                            ("evt-change", Decimal("3.00"), Jsonb({})),
                            ("evt-ancillary", Decimal("2.00"), Jsonb({})),
                        ],
                    )
            with psycopg.connect(source_url) as source:
                for table in (
                    "payment_intent_snapshots", "journey_order_snapshots", "post_sales_case_snapshots",
                    "segment_booking_snapshots", "seat_allocations", "ancillary_order_items", "policy_snapshots",
                ):
                    source.execute(sql.SQL("CREATE TABLE {}(id text PRIMARY KEY,data jsonb NOT NULL,updated_at timestamptz NOT NULL)").format(sql.Identifier(table)))
                with source.cursor() as cursor:
                    cursor.executemany(
                        "INSERT INTO payment_intent_snapshots VALUES (%s,%s,'2026-09-25T04:00:00Z')",
                        [
                            ("pi-purchase", Jsonb({"paymentIntentId": "pi-purchase", "businessRef": "ord-1", "purpose": "purchase", "domainEvents": [{"eventId": "evt-purchase", "eventType": "PaymentCaptured", "occurredAt": "2026-09-25T04:00:00Z", "payload": {}}]})),
                            ("pi-change", Jsonb({"paymentIntentId": "pi-change", "businessRef": "psc-case-1", "purpose": "change", "domainEvents": [{"eventId": "evt-change", "eventType": "PaymentCaptured", "occurredAt": "2026-09-25T04:00:00Z", "payload": {}}]})),
                            ("pi-ancillary", Jsonb({"paymentIntentId": "pi-ancillary", "businessRef": "aoi-1", "purpose": "ancillary", "domainEvents": [{"eventId": "evt-ancillary", "eventType": "PaymentCaptured", "occurredAt": "2026-09-25T04:00:00Z", "payload": {}}]})),
                        ],
                    )
                source.execute(
                    "INSERT INTO journey_order_snapshots VALUES ('ord-1',%s,'2026-09-25T03:50:00Z')",
                    (Jsonb({
                        "orderId": "ord-1",
                        "segments": [
                            {"segmentRef": "seg-a", "departureTime": "2026-10-01T09:00:00Z"},
                            {"segmentRef": "seg-b", "departureTime": "2026-10-02T09:00:00Z"},
                        ],
                        "travelers": [
                            {"travelerId": "tvl-1", "travelerType": "ADULT"},
                            {"travelerId": "tvl-2", "travelerType": "CHILD"},
                        ],
                    }),),
                )
                source.execute(
                    "INSERT INTO post_sales_case_snapshots VALUES ('case-1',%s,'2026-09-25T03:55:00Z')",
                    (Jsonb({"caseId": "case-1", "journeyOrderId": "ord-1", "scope": {"segmentRefs": ["seg-b"], "travelerRefs": ["tvl-2"]}}),),
                )
                source.execute(
                    "INSERT INTO ancillary_order_items VALUES ('aoi-1',%s,'2026-09-25T03:56:00Z')",
                    (Jsonb({"ancillaryOrderItemId": "aoi-1", "journeyOrderId": "ord-1", "segmentRef": "seg-a", "travelerRef": "tvl-1", "serviceType": "MEAL", "status": "CONFIRMED", "paymentIntentId": "pi-ancillary"}),),
                )
                source.execute(
                    "INSERT INTO segment_booking_snapshots VALUES ('sb-1',%s,'2026-09-25T03:57:00Z')",
                    (Jsonb({"journeyOrderId": "ord-1", "segmentRef": "seg-a", "travelerRef": "tvl-1", "classRef": "SECOND_CLASS"}),),
                )
                source.execute(
                    "INSERT INTO seat_allocations VALUES ('sa-1',%s,'2026-09-25T03:58:00Z')",
                    (Jsonb({"journeyOrderId": "ord-1", "segmentRef": "seg-a", "travelerRef": "tvl-1", "requestedClassRef": "FIRST_CLASS"}),),
                )

            source_env = {
                variable: source_url
                for variable in (
                    "PAYMENT_DATABASE_URL", "JOURNEY_ORDER_DATABASE_URL", "POST_SALES_DATABASE_URL",
                    "BOOKING_ORCHESTRATION_DATABASE_URL", "SEAT_ASSIGNMENT_DATABASE_URL",
                    "ANCILLARY_SERVICE_DATABASE_URL", "TRAVEL_INSURANCE_DATABASE_URL",
                )
            }
            first = migrate_module.migrate(
                reporting_url, schema=reporting_schema, batch_size=1, batch_delay_ms=0, source_env=source_env
            )
            second = migrate_module.migrate(
                reporting_url, schema=reporting_schema, batch_size=2, batch_delay_ms=0, source_env=source_env
            )
            assert first["setupApplied"] and first["viewsApplied"]
            assert not second["setupApplied"] and not second["viewsApplied"]
            assert first["backfill"]["captured_events"] == first["backfill"]["referenced_captures"] == 3
            assert second["backfill"]["captured_events"] == second["backfill"]["referenced_captures"] == 3
            with psycopg.connect(reporting_url) as target:
                assert target.execute("SELECT count(*) FROM reporting_capture_references").fetchone()[0] == 3
                assert target.execute("SELECT seat_class,source_priority FROM reporting_seat_classes WHERE order_id='ord-1'").fetchone() == ("FIRST_CLASS", 2)
                assert target.execute("SELECT attachment_type FROM reporting_order_attachments WHERE order_id='ord-1'").fetchone()[0] == "ANCILLARY"
                assert target.execute("SELECT segment_refs,traveler_refs FROM reporting_business_scopes WHERE business_ref='psc-case-1'").fetchone() == (["seg-b"], ["tvl-2"])
                assert target.execute(
                    "SELECT count(*) FROM schema_migrations WHERE version=ANY(%s)",
                    (["007_authoritative_revenue_facts.sql", "008_authoritative_revenue_views.sql"],),
                ).fetchone()[0] == 2
                for dimension in ("route", "traveler_type", "seat_class", "channel", "time_period"):
                    assert target.execute(
                        "SELECT sum(revenue) FROM reporting_revenue_breakdowns WHERE dimension=%s", (dimension,)
                    ).fetchone()[0] == Decimal("15.01")
        finally:
            admin.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(reporting_schema)))
            admin.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(source_schema)))
