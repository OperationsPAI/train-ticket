import json
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from psycopg_pool import ConnectionPool

from identity_verification import create_app
from identity_verification.adapters.storage.postgres import PostgresIdentityVerificationStore, _UNIT_OF_WORK
from train_ticket_platform.ids import new_uuid7
from train_ticket_platform.storage import PostgresIdempotencyStore


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_IDENTITY_HTTP_SOURCE"), reason="requires PostgreSQL and a recorded credential")
def test_http_context_preserves_concurrent_transactions_replays_and_tracing():
    credential = json.loads(Path(os.environ["TEST_IDENTITY_HTTP_SOURCE"]).read_text())
    body = {key: credential[key] for key in (
        "travelerId", "profileSnapshotVersion", "documentType", "maskedDocumentNo", "documentHash",
        "canonicalNameHash", "birthDateHash", "validUntil", "evidenceHash",
    )}
    schema = "identity_http_" + uuid4().hex
    provider = TracerProvider()
    exporter = InMemorySpanExporter()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    traces = []

    def record(event, attributes):
        traces.append((event, attributes, _UNIT_OF_WORK.get()))

    with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(os.environ["TEST_DATABASE_URL"], min_size=1, max_size=8, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    for migration in sorted((Path(__file__).parents[1] / "migrations").glob("*.sql")):
                        conn.execute(migration.read_text())
                app = create_app(store=PostgresIdentityVerificationStore(pool), idempotency_store=PostgresIdempotencyStore(pool),
                                 tracer=record, otel_tracer=provider.get_tracer("identity-http-test"))
                path = "/api/v1/identity-verification/credentials"
                with TestClient(app) as client:
                    def register(_):
                        request_id, correlation_id = new_uuid7(), new_uuid7()
                        headers = {"Idempotency-Key": new_uuid7(), "X-Request-ID": request_id, "x-correlation-id": correlation_id}
                        response = client.post(path, json=body, headers=headers)
                        assert response.status_code == 201, response.text
                        assert response.headers["X-Request-ID"] == request_id
                        assert response.headers["X-Correlation-Id"] == correlation_id
                        assert len(response.headers.get_list("X-Correlation-Id")) == 1
                        return response, headers

                    with ThreadPoolExecutor(max_workers=8) as workers:
                        results = list(workers.map(register, range(8)))
                    assert len({response.json()["credentialRecordId"] for response, _ in results}) == 1
                    response, headers = results[0]
                    headers = {**headers, "X-Request-ID": new_uuid7(), "x-correlation-id": new_uuid7()}
                    replay = client.post(path, json=body, headers=headers)
                    assert replay.status_code == 201
                    assert replay.json() == response.json()
                    assert replay.headers["X-Request-ID"] == headers["X-Request-ID"]
                    assert replay.headers["X-Correlation-Id"] == headers["x-correlation-id"]
                    conflict = client.post(path, json={**body, "profileSnapshotVersion": new_uuid7()}, headers=headers)
                    assert conflict.status_code == 422
                    missing_key = client.post(path, json=body)
                    assert missing_key.status_code == 400
                    assert missing_key.headers["X-Request-ID"]
                    missing_credential = client.get(path + "/crd-" + new_uuid7() + "/verification-status")
                    assert missing_credential.status_code == 404
                    assert client.get("/readyz").status_code == 200
                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM credential_record_snapshots").fetchone()[0] == 1
                    assert conn.execute("SELECT count(*) FROM outbox").fetchone()[0] == 1
                starts = [item for item in traces if item[0] == "http.request.start"]
                completions = [item for item in traces if item[0] == "http.request.complete"]
                assert len(starts) == len(completions) == 13
                assert len({id(state) for _, _, state in starts}) == 13
                assert all(state is not None and state.connection is None for _, _, state in traces)
                assert all(start[2] is completion[2] for start, completion in zip(starts[8:], completions[8:]))
                assert _UNIT_OF_WORK.get() is None
                spans = exporter.get_finished_spans()
                assert len(spans) == 13
                assert sorted(span.attributes["http.response.status_code"] for span in spans) == [200, 201, 201, 201, 201, 201, 201, 201, 201, 201, 400, 404, 422]
                assert all(span.attributes["request_id"] and span.attributes["correlation_id"] for span in spans)
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
            provider.shutdown()
