import json
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from fastapi.testclient import TestClient
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool
from starlette.background import BackgroundTask

from train_ticket_platform.idempotency import configure_idempotency_middleware
from train_ticket_platform.ids import new_uuid7
from train_ticket_platform.storage import PostgresIdempotencyStore


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_OUTBOX_EVENTS"), reason="requires PostgreSQL and recorded events")
def test_chunked_response_commits_before_success_and_runs_background_once():
    event = json.loads(Path(os.environ["TEST_OUTBOX_EVENTS"]).read_text())[0]
    schema = "idempotency_stream_" + uuid4().hex
    with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(os.environ["TEST_DATABASE_URL"], min_size=1, max_size=4, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    conn.execute("CREATE TABLE idempotency_records(key text PRIMARY KEY,request_hash text,status_code integer,response_body jsonb)")
                    conn.execute("CREATE TABLE events(id text PRIMARY KEY, envelope jsonb)")
                    conn.execute("CREATE TABLE completed_responses(event_id text)")
                    conn.execute("INSERT INTO events VALUES (%s,%s)", (event["eventId"], Jsonb(event)))
                app = FastAPI()
                configure_idempotency_middleware(app, PostgresIdempotencyStore(pool), include_path_prefixes=("/events",))

                def completed():
                    with pool.connection() as conn:
                        conn.execute("INSERT INTO completed_responses VALUES (%s)", (event["eventId"],))

                @app.post("/events")
                def read_recorded_event(fail: bool = False):
                    with pool.connection() as conn:
                        envelope = conn.execute("SELECT envelope FROM events WHERE id=%s", (event["eventId"],)).fetchone()[0]
                    content = json.dumps(envelope, ensure_ascii=False).encode()

                    async def chunks():
                        for start in range(0, len(content), 13):
                            yield content[start:start + 13]
                            if fail:
                                raise ValueError("response generation failed")

                    return StreamingResponse(chunks(), status_code=201, media_type="application/json", background=BackgroundTask(completed))

                with TestClient(app, raise_server_exceptions=False) as client:
                    key = new_uuid7()
                    headers = {"Idempotency-Key": key}
                    failed_stream = client.post("/events?fail=true", headers={"Idempotency-Key": new_uuid7()})
                    assert failed_stream.status_code == 500
                    with pool.connection() as conn:
                        assert conn.execute("SELECT count(*) FROM idempotency_records").fetchone()[0] == 0
                        conn.execute("ALTER TABLE idempotency_records ADD CONSTRAINT reject_success CHECK(status_code=0)")
                    failed_commit = client.post("/events", headers=headers)
                    assert failed_commit.status_code == 500
                    with pool.connection() as conn:
                        assert conn.execute("SELECT count(*) FROM completed_responses").fetchone()[0] == 0
                        conn.execute("ALTER TABLE idempotency_records DROP CONSTRAINT reject_success")
                    response = client.post("/events", headers=headers)
                    assert response.status_code == 201
                    assert response.json() == event
                    replay = client.post("/events", headers=headers)
                    assert replay.status_code == 201
                    assert replay.json() == event
                    with pool.connection() as conn:
                        assert conn.execute("SELECT response_body FROM idempotency_records").fetchone()[0] == event
                        assert conn.execute("SELECT count(*) FROM completed_responses").fetchone()[0] == 1
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
