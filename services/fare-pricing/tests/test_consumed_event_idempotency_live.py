from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, date, datetime, timedelta
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg_pool import ConnectionPool

from fare_pricing.adapters.messaging.handlers import handle_capacity_snapshot_updated, handle_identity_verification_event
from fare_pricing.adapters.storage.postgres import PostgresFarePricingStore, _certificate_from_json
from fare_pricing.application.service import FarePricingService
from fare_pricing.ports import EventEnvelope
from train_ticket_platform.messaging import FatalHandlerError


def capacity_class(class_ref: str, remaining: int, effective: int = 120) -> dict[str, object]:
    occupied = effective - remaining
    return {
        "classRef": class_ref,
        "physicalCapacity": effective,
        "effectiveCapacity": effective,
        "holdCount": occupied,
        "confirmedCount": 0,
        "remainingCapacity": remaining,
        "overbookingPolicy": {
            "maxOverbookingPct": 0.0,
            "noShowRate": 0.0,
            "safetyMarginPct": 0.0,
        },
    }


def capacity_payload(
    segment_ref: str,
    departure_date: date,
    classes: list[dict[str, object]],
    snapshot_version: str = "3",
) -> dict[str, object]:
    total = sum(int(item["effectiveCapacity"]) for item in classes)
    remaining = sum(int(item["remainingCapacity"]) for item in classes)
    hold_count = sum(int(item["holdCount"]) for item in classes)
    confirmed_count = sum(int(item["confirmedCount"]) for item in classes)
    return {
        "segmentRef": segment_ref,
        "departureDate": departure_date.isoformat(),
        "totalCapacity": total,
        "remainingCapacity": remaining,
        "physicalCapacity": sum(int(item["physicalCapacity"]) for item in classes),
        "holdCount": hold_count,
        "confirmedCount": confirmed_count,
        "utilizationPct": ((hold_count + confirmed_count) * 100 / total) if total else 0,
        "snapshotVersion": snapshot_version,
        "classes": classes,
    }


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="requires PostgreSQL via TEST_DATABASE_URL")
def test_same_consumed_event_is_atomic_across_workers_and_restarts() -> None:
    schema = "fare_event_dedup_" + uuid4().hex
    migrations = Path(__file__).parents[1] / "migrations"
    database_url = os.environ["TEST_DATABASE_URL"]
    with psycopg.connect(database_url, autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(
                database_url,
                min_size=1,
                max_size=16,
                kwargs={"options": f"-c search_path={schema}"},
            ) as pool:
                with pool.connection() as conn:
                    for migration in sorted(migrations.glob("*.sql")):
                        conn.execute(migration.read_text())

                store = PostgresFarePricingStore(pool)
                service = FarePricingService(store)
                payload = capacity_payload(
                    "seg-live-dedup",
                    date(2026, 10, 1),
                    [capacity_class("SECOND_CLASS", 41)],
                )
                event = EventEnvelope(
                    event_id="evt-live-capacity-dedup",
                    event_type="CapacitySnapshotUpdated",
                    producer="capacity-availability",
                    correlation_id="corr-live-capacity-dedup",
                    occurred_at=datetime(2026, 9, 25, 4, 0, tzinfo=UTC),
                    payload=payload,
                )

                with ThreadPoolExecutor(max_workers=16) as workers:
                    list(workers.map(lambda _: handle_capacity_snapshot_updated(service, event), range(32)))

                restarted_service = FarePricingService(PostgresFarePricingStore(pool))
                handle_capacity_snapshot_updated(restarted_service, event)

                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM processed_events").fetchone()[0] == 1
                    assert conn.execute("SELECT count(*) FROM capacity_snapshot_cache").fetchone()[0] == 1
                    assert conn.execute(
                        "SELECT remaining_capacity,snapshot_version FROM capacity_snapshot_cache"
                    ).fetchone() == (41, "3")
                    assert conn.execute(
                        "SELECT event_fingerprint IS NOT NULL FROM processed_events WHERE event_id=%s",
                        (event.eventId,),
                    ).fetchone()[0]

                changed = EventEnvelope(
                    event_id=event.eventId,
                    event_type=event.eventType,
                    producer=event.producer,
                    correlation_id="corr-redelivered",
                    occurred_at=datetime(2026, 9, 25, 4, 1, tzinfo=UTC),
                    payload=capacity_payload(
                        "seg-live-dedup",
                        date(2026, 10, 1),
                        [capacity_class("SECOND_CLASS", 40)],
                    ),
                )
                with pytest.raises(FatalHandlerError, match="different content"):
                    handle_capacity_snapshot_updated(restarted_service, changed)

                with pool.connection() as conn:
                    assert conn.execute(
                        "SELECT remaining_capacity FROM capacity_snapshot_cache"
                    ).fetchone()[0] == 41
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="requires PostgreSQL via TEST_DATABASE_URL")
def test_capacity_classes_same_version_changes_and_out_of_order_events_converge_under_concurrency() -> None:
    schema = "fare_snapshot_idem_" + uuid4().hex
    migrations = Path(__file__).parents[1] / "migrations"
    database_url = os.environ["TEST_DATABASE_URL"]
    with psycopg.connect(database_url, autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(
                database_url,
                min_size=1,
                max_size=16,
                kwargs={"options": f"-c search_path={schema}"},
            ) as pool:
                with pool.connection() as conn:
                    for migration in sorted(migrations.glob("*.sql")):
                        conn.execute(migration.read_text())

                service = FarePricingService(PostgresFarePricingStore(pool))
                segment_ref = "seg-live-business-key"
                departure_date = date(2026, 10, 2)
                base_time = datetime(2026, 9, 25, 4, 5, tzinfo=UTC)
                initial = capacity_payload(
                    segment_ref,
                    departure_date,
                    [capacity_class("standard", 100), capacity_class("FIRST_CLASS", 30, 60)],
                    "3",
                )

                def capacity_event(index: int, occurred_at: datetime, payload: dict[str, object]) -> EventEnvelope:
                    return EventEnvelope(
                        event_id=f"evt-0199c26b-7000-7000-8000-{index:012d}",
                        event_type="CapacitySnapshotUpdated",
                        producer="capacity-availability",
                        correlation_id="corr-live-capacity-business",
                        occurred_at=occurred_at,
                        payload=payload,
                    )

                handle_capacity_snapshot_updated(service, capacity_event(0, base_time, initial))
                concurrent_events = [
                    capacity_event(
                        index,
                        base_time + timedelta(milliseconds=index),
                        capacity_payload(
                            segment_ref,
                            departure_date,
                            [capacity_class("SECOND_CLASS", 100 - index)],
                            "3",
                        ),
                    )
                    for index in range(1, 33)
                ]
                with ThreadPoolExecutor(max_workers=16) as workers:
                    list(workers.map(lambda event: handle_capacity_snapshot_updated(service, event), reversed(concurrent_events)))

                stale = capacity_payload(
                    segment_ref,
                    departure_date,
                    [capacity_class("SECOND_CLASS", 1)],
                    "999",
                )
                handle_capacity_snapshot_updated(
                    service,
                    capacity_event(40, base_time + timedelta(milliseconds=15), stale),
                )

                with pool.connection() as conn:
                    rows = conn.execute(
                        "SELECT class_ref,remaining_capacity,snapshot_version,source_event_id "
                        "FROM capacity_snapshot_cache ORDER BY class_ref"
                    ).fetchall()
                    assert rows == [
                        ("FIRST_CLASS", 30, "3", "evt-0199c26b-7000-7000-8000-000000000000"),
                        ("SECOND_CLASS", 68, "3", "evt-0199c26b-7000-7000-8000-000000000032"),
                    ]
                    assert conn.execute("SELECT count(*) FROM processed_events").fetchone()[0] == 34

                first_class = service.capacity_snapshots_for(
                    [segment_ref], departure_date.isoformat(), "FIRST_CLASS"
                )
                second_class = service.capacity_snapshots_for(
                    [segment_ref], departure_date.isoformat(), "standard"
                )
                assert [(item.class_ref, item.remaining_capacity) for item in first_class] == [
                    ("FIRST_CLASS", 30)
                ]
                assert [(item.class_ref, item.remaining_capacity) for item in second_class] == [
                    ("SECOND_CLASS", 68)
                ]
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="requires PostgreSQL via TEST_DATABASE_URL")
def test_eligibility_business_snapshot_replays_serialize_without_hiding_conflicts() -> None:
    schema = "fare_eligibility_idem_" + uuid4().hex
    migrations = Path(__file__).parents[1] / "migrations"
    database_url = os.environ["TEST_DATABASE_URL"]
    with psycopg.connect(database_url, autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(
                database_url,
                min_size=1,
                max_size=16,
                kwargs={"options": f"-c search_path={schema}"},
            ) as pool:
                with pool.connection() as conn:
                    for migration in sorted(migrations.glob("*.sql")):
                        conn.execute(migration.read_text())

                service = FarePricingService(PostgresFarePricingStore(pool))

                registered_payload = {
                    "eligibilityCertificateId": "elc-live-business-key",
                    "travelerId": "tvl-live-business-key",
                    "credentialRecordId": "crd-live-business-key",
                    "eligibilityType": "STUDENT",
                    "certificateStatus": "ACTIVE",
                    "validFrom": "2026-01-01T00:00:00Z",
                    "validUntil": "2027-01-01T00:00:00Z",
                    "policyYear": "2026",
                    "policyVersion": "student-2026",
                    "annualUsageLimit": 4,
                    "annualUsageReserved": 0,
                    "annualUsageConfirmed": 0,
                    "applicableProductCodes": ["rail-standard"],
                    "aggregateVersion": 2,
                }

                def eligibility_event(index: int, payload: dict[str, object] = registered_payload) -> EventEnvelope:
                    return EventEnvelope(
                        event_id=f"evt-live-eligibility-business-{index}",
                        event_type="EligibilityCertificateVerified",
                        producer="identity-verification",
                        correlation_id="corr-live-eligibility-business",
                        occurred_at=datetime(2026, 9, 25, 4, 6, tzinfo=UTC),
                        payload=payload,
                    )

                with ThreadPoolExecutor(max_workers=16) as workers:
                    list(workers.map(lambda index: handle_identity_verification_event(service, eligibility_event(index)), range(32)))

                certificate_conflict = {**registered_payload, "annualUsageLimit": 5}
                with pytest.raises(FatalHandlerError, match="conflicting content"):
                    handle_identity_verification_event(service, eligibility_event(40, certificate_conflict))

                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM eligibility_certificate_cache").fetchone()[0] == 1
                    row = conn.execute(
                        "SELECT data FROM eligibility_certificate_cache WHERE id=%s",
                        (registered_payload["eligibilityCertificateId"],),
                    ).fetchone()
                    assert _certificate_from_json(row[0]).annual_usage_limit == 4
                    assert conn.execute(
                        "SELECT count(*) FROM processed_events WHERE event_id=%s",
                        ("evt-live-eligibility-business-40",),
                    ).fetchone()[0] == 0
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
