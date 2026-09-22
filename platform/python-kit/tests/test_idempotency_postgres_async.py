import os
import threading
import time
from uuid import uuid4

import psycopg
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from psycopg_pool import ConnectionPool
from psycopg.types.json import Jsonb

from train_ticket_platform.idempotency import configure_idempotency_middleware, http_request_fingerprint
from train_ticket_platform.ids import new_uuid7
from train_ticket_platform.storage import PostgresIdempotencyStore


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="TEST_DATABASE_URL is required")
def test_path_scoped_replay_persists_across_apps_with_one_lookup_per_request():
    url = os.environ["TEST_DATABASE_URL"]
    schema = "idem_test_" + uuid4().hex
    lookups = []

    class ObservedConnection(psycopg.Connection):
        def execute(self, query, params=None, **kwargs):
            if isinstance(query, str) and query.startswith("SELECT request_hash, status_code, response_body"):
                lookups.append(params)
            return super().execute(query, params, **kwargs)

    with psycopg.connect(url, autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(url, connection_class=ObservedConnection, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    conn.execute("CREATE TABLE idempotency_records(key text PRIMARY KEY,request_hash text,status_code integer,response_body jsonb)")
                    conn.execute("CREATE TABLE commands(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,payload jsonb NOT NULL)")

                def create_app():
                    app = FastAPI()
                    configure_idempotency_middleware(app, PostgresIdempotencyStore(pool), include_path_prefixes=("/commands",))

                    @app.post("/commands", status_code=201)
                    @app.post("/commands/other", status_code=201)
                    def command(payload: dict):
                        with pool.connection() as conn:
                            command_id = conn.execute("INSERT INTO commands(payload) VALUES (%s) RETURNING id", (Jsonb(payload),)).fetchone()[0]
                        return {"id": command_id, "payload": payload}

                    return app

                key = new_uuid7()
                headers = {"Idempotency-Key": key, "Content-Type": "application/json"}
                body = b'{"value":1}'
                with TestClient(create_app()) as first_client:
                    first = first_client.post("/commands", content=body, headers=headers)
                assert first.status_code == 201
                assert lookups == [(f"/commands\x1f{key}",)]

                with TestClient(create_app()) as second_client:
                    replay = second_client.post("/commands", content=body, headers=headers)
                    conflict = second_client.post("/commands", content=b'{"value":2}', headers=headers)
                    other = second_client.post("/commands/other", content=body, headers=headers)

                assert replay.status_code == 201
                assert replay.json() == first.json()
                assert conflict.status_code == 422
                assert conflict.json()["code"] == "IDEMPOTENCY_KEY_REUSED"
                assert other.status_code == 201
                assert other.json()["id"] != first.json()["id"]
                assert lookups == [(f"/commands\x1f{key}",)] * 3 + [(f"/commands/other\x1f{key}",)]
                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM commands").fetchone()[0] == 2
                    records = conn.execute("SELECT key, request_hash, status_code, response_body FROM idempotency_records ORDER BY key").fetchall()
                assert records == [
                    (f"/commands\x1f{key}", http_request_fingerprint("POST", "/commands", "", body), 201, first.json()),
                    (f"/commands/other\x1f{key}", http_request_fingerprint("POST", "/commands/other", "", body), 201, other.json()),
                ]
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="TEST_DATABASE_URL is required")
def test_health_remains_responsive_while_idempotency_database_waits():
    url = os.environ["TEST_DATABASE_URL"]
    schema = "idem_test_" + uuid4().hex
    with psycopg.connect(url, autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(url, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    conn.execute("CREATE TABLE idempotency_records(key text PRIMARY KEY,request_hash text,status_code integer,response_body jsonb)")
                app = FastAPI()
                configure_idempotency_middleware(app, PostgresIdempotencyStore(pool), include_path_prefixes=("/commands",))

                @app.post("/commands")
                async def command():
                    return {"ok": True}

                @app.get("/healthz")
                async def health():
                    return {"ok": True}

                with TestClient(app) as client, pool.connection() as blocker:
                    blocker.execute("LOCK TABLE idempotency_records IN ACCESS EXCLUSIVE MODE")
                    responses = []
                    worker = threading.Thread(target=lambda: responses.append(client.post("/commands", json={}, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c001"})))
                    worker.start()
                    try:
                        deadline = time.monotonic() + 5
                        while time.monotonic() < deadline:
                            waiting = admin.execute("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT request_hash%'").fetchone()[0]
                            if waiting:
                                break
                            time.sleep(0.01)
                        assert waiting > 0
                        started = time.monotonic()
                        assert client.get("/healthz").status_code == 200
                        assert time.monotonic() - started < 0.5
                    finally:
                        blocker.rollback()
                        worker.join(timeout=10)
                    assert responses[0].status_code == 200
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
