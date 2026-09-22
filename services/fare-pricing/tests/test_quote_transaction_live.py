import json
import os
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg_pool import ConnectionPool

from fare_pricing.adapters.storage.postgres import PostgresFarePricingStore, _certificate_from_json
from fare_pricing.application.service import FarePricingService
from fare_pricing.web.handlers import _fare_quote_event_payload
from train_ticket_platform.events import EventEnvelope


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_QUOTED_ELIGIBILITY"), reason="requires PostgreSQL and recorded pricing inputs")
def test_quote_links_and_events_commit_together_without_reading_back_new_quote():
    source = json.loads(Path(os.environ["TEST_QUOTED_ELIGIBILITY"]).read_text())
    schema = "fare_pipeline_" + uuid4().hex
    selects = []

    class ObservedConnection(psycopg.Connection):
        def execute(self, query, params=None, **kwargs):
            if isinstance(query, str) and query.startswith("SELECT version, data FROM fare_quote_snapshots"):
                selects.append(query)
            return super().execute(query, params, **kwargs)

    with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(os.environ["TEST_DATABASE_URL"], min_size=1, max_size=1, connection_class=ObservedConnection, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    for migration in sorted((Path(__file__).parents[1] / "migrations").glob("*.sql")):
                        conn.execute(migration.read_text())
                    conn.execute("INSERT INTO fare_rule_set_snapshots(id,version,data) VALUES (%s,1,%s::jsonb)", (source["rule"]["ruleSetId"], json.dumps(source["rule"])))
                    conn.execute("ALTER TABLE outbox ADD CONSTRAINT reject_quote CHECK(envelope->>'eventType'<>'FareQuoteComputed')")
                store = PostgresFarePricingStore(pool)
                with store.transaction():
                    store.upsert_eligibility_certificate(_certificate_from_json({**source["certificate"], "certificateStatus": source["certificate"]["status"]}))
                service = FarePricingService(store)
                segment = source["purchase"]["seg"]
                quote_id = "fq-" + uuid4().hex

                def quote():
                    with service.transaction():
                        result = service.compute_fare_quote(quote_id, "input-" + uuid4().hex, [source["credential"]["travelerId"]], source["rule"]["channel"], source["rule"]["ruleSetId"], "CNY",
                            segment_refs=[segment, segment], departure_time=datetime.fromisoformat(source["purchase"]["journey_date"]).replace(tzinfo=UTC))
                        service.append_outbox((EventEnvelope(eventType="FareQuoteComputed", producer="fare-pricing", payload=_fare_quote_event_payload(result)),))
                        return result

                with pytest.raises(psycopg.errors.CheckViolation):
                    quote()
                with pool.connection() as conn:
                    for table in ("fare_quote_snapshots", "fare_quote_segment_links", "outbox"):
                        assert conn.execute(f"SELECT count(*) FROM {table}").fetchone()[0] == 0
                    conn.execute("ALTER TABLE outbox DROP CONSTRAINT reject_quote")
                created = quote()
                assert selects == []
                with store.transaction():
                    store.link_fare_quote_to_segments(created.quote_id, [segment, segment])
                with pool.connection() as conn:
                    assert conn.execute("SELECT quote_id,segment_ref FROM fare_quote_segment_links").fetchall() == [(quote_id, segment)]
                    assert conn.execute("SELECT envelope->'payload'->>'quoteId' FROM outbox").fetchone()[0] == quote_id
                    assert conn.pgconn.pipeline_status == psycopg.pq.PipelineStatus.OFF
                assert store.get_quote(quote_id) == created
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
