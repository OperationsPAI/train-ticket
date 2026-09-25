from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, date, datetime
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg_pool import ConnectionPool

from fare_pricing.adapters.messaging.handlers import handle_capacity_snapshot_updated
from fare_pricing.adapters.messaging.handlers import handle_identity_verification_event
from fare_pricing.adapters.storage.postgres import PostgresFarePricingStore, _certificate_from_json
from fare_pricing.application.service import FarePricingService
from fare_pricing.ports import EventEnvelope
from train_ticket_platform.messaging import FatalHandlerError


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
                payload = {
                    "segmentRef": "seg-live-dedup",
                    "departureDate": date(2026, 10, 1).isoformat(),
                    "totalCapacity": 120,
                    "remainingCapacity": 41,
                    "snapshotVersion": 12,
                }
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
                    ).fetchone() == (41, 12)
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
                    payload={**payload, "remainingCapacity": 40},
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
def test_business_snapshot_replays_serialize_without_hiding_conflicts() -> None:
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
                capacity_payload = {
                    "segmentRef": "seg-live-business-key",
                    "departureDate": date(2026, 10, 2).isoformat(),
                    "totalCapacity": 120,
                    "remainingCapacity": 41,
                    "snapshotVersion": 12,
                }

                def capacity_event(index: int, payload: dict[str, object] = capacity_payload) -> EventEnvelope:
                    return EventEnvelope(
                        event_id=f"evt-live-capacity-business-{index}",
                        event_type="CapacitySnapshotUpdated",
                        producer="capacity-availability",
                        correlation_id="corr-live-capacity-business",
                        occurred_at=datetime(2026, 9, 25, 4, 5, tzinfo=UTC),
                        payload=payload,
                    )

                with ThreadPoolExecutor(max_workers=16) as workers:
                    list(workers.map(lambda index: handle_capacity_snapshot_updated(service, capacity_event(index)), range(32)))

                stale = {**capacity_payload, "remainingCapacity": 90, "snapshotVersion": 11}
                handle_capacity_snapshot_updated(service, capacity_event(40, stale))
                conflicting = {**capacity_payload, "remainingCapacity": 40}
                with pytest.raises(FatalHandlerError, match="conflicting content"):
                    handle_capacity_snapshot_updated(service, capacity_event(41, conflicting))

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
                    assert conn.execute("SELECT count(*) FROM capacity_snapshot_cache").fetchone()[0] == 1
                    assert conn.execute(
                        "SELECT remaining_capacity,snapshot_version FROM capacity_snapshot_cache"
                    ).fetchone() == (41, 12)
                    assert conn.execute("SELECT count(*) FROM eligibility_certificate_cache").fetchone()[0] == 1
                    row = conn.execute(
                        "SELECT data FROM eligibility_certificate_cache WHERE id=%s",
                        (registered_payload["eligibilityCertificateId"],),
                    ).fetchone()
                    assert _certificate_from_json(row[0]).annual_usage_limit == 4
                    assert conn.execute(
                        "SELECT count(*) FROM processed_events WHERE event_id IN (%s,%s)",
                        ("evt-live-capacity-business-41", "evt-live-eligibility-business-40"),
                    ).fetchone()[0] == 0
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
