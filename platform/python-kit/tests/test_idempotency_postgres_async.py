import os
import threading
import time
from uuid import uuid4

import psycopg
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from psycopg_pool import ConnectionPool

from train_ticket_platform.idempotency import configure_idempotency_middleware
from train_ticket_platform.storage import PostgresIdempotencyStore


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
