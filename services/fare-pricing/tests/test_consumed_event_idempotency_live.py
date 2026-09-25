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
from fare_pricing.adapters.storage.postgres import PostgresFarePricingStore
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
