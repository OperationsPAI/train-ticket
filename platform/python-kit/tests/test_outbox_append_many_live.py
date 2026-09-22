import json
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg_pool import ConnectionPool

from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.storage import OutboxAppender


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_OUTBOX_EVENTS"), reason="requires PostgreSQL and recorded events")
def test_batch_append_preserves_order_envelopes_dedup_and_atomicity():
    source = json.loads(Path(os.environ["TEST_OUTBOX_EVENTS"]).read_text())[:503]
    events = [EventEnvelope(**event) for event in source]
    schema = "outbox_append_" + uuid4().hex
    with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(os.environ["TEST_DATABASE_URL"], min_size=1, max_size=2, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    conn.execute("CREATE TABLE outbox(seq bigserial PRIMARY KEY,event_id text UNIQUE,stream text,envelope jsonb)")
                    conn.execute("CREATE TABLE business_facts(id text PRIMARY KEY)")
                    conn.execute(psycopg.sql.SQL("ALTER TABLE outbox ADD CONSTRAINT reject_event CHECK(event_id <> {})").format(psycopg.sql.Literal(events[250].eventId)))
                appender = OutboxAppender()
                with pytest.raises(psycopg.errors.CheckViolation):
                    with pool.connection() as conn:
                        conn.execute("INSERT INTO business_facts VALUES (%s)", (events[0].eventId,))
                        appender.append_many(conn, iter(events))
                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM business_facts").fetchone()[0] == 0
                    assert conn.execute("SELECT count(*) FROM outbox").fetchone()[0] == 0
                    conn.execute("ALTER TABLE outbox DROP CONSTRAINT reject_event")
                    appender.append_many(conn, events)
                    appender.append_many(conn, events)
                    appender.append_many(conn, ())
                    rows = conn.execute("SELECT event_id,stream,envelope FROM outbox ORDER BY seq").fetchall()
                    assert len(rows) == 503
                    assert [row[2] for row in rows] == source
                    assert [row[1] for row in rows] == ["events:" + event.producer for event in events]
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
