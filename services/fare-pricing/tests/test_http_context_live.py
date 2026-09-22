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

from fare_pricing.api import create_app
from fare_pricing.adapters.storage.postgres import PostgresFarePricingStore, _certificate_from_json, _UNIT_OF_WORK
from train_ticket_platform.ids import new_uuid7
from train_ticket_platform.storage import PostgresIdempotencyStore


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_QUOTED_ELIGIBILITY"), reason="requires PostgreSQL and recorded pricing inputs")
def test_http_context_preserves_concurrent_quotes_replays_tracing_and_rollback(monkeypatch):
    source = json.loads(Path(os.environ["TEST_QUOTED_ELIGIBILITY"]).read_text())
    schema = "fare_http_" + uuid4().hex
    provider = TracerProvider()
    exporter = InMemorySpanExporter()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    traces = []
    monkeypatch.setenv("FARE_PRICING_DISABLE_SUBSCRIBER", "1")

    def record(event, attributes):
        traces.append((event, attributes, _UNIT_OF_WORK.get()))

    with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(os.environ["TEST_DATABASE_URL"], min_size=1, max_size=8, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    for migration in sorted((Path(__file__).parents[1] / "migrations").glob("*.sql")):
                        conn.execute(migration.read_text())
                    conn.execute("INSERT INTO fare_rule_set_snapshots(id,version,data) VALUES (%s,1,%s::jsonb)", (source["rule"]["ruleSetId"], json.dumps(source["rule"])))
                store = PostgresFarePricingStore(pool)
                with store.transaction():
                    store.upsert_eligibility_certificate(_certificate_from_json({**source["certificate"], "certificateStatus": source["certificate"]["status"]}))
                app = create_app(store=store, idempotency_store=PostgresIdempotencyStore(pool), tracer=record, otel_tracer=provider.get_tracer("fare-http-test"))
                body = {"travelerRefs": [source["credential"]["travelerId"]], "channel": source["rule"]["channel"], "productCode": source["rule"]["productCode"],
                        "segmentRefs": [source["purchase"]["seg"]], "departureTime": source["purchase"]["journey_date"] + "T00:00:00Z"}
                path = "/api/v1/fare-quotes"
                with TestClient(app, raise_server_exceptions=False) as client:
                    def quote(_):
                        headers = {"Idempotency-Key": new_uuid7(), "X-Request-ID": new_uuid7(), "X-Correlation-Id": "corr-" + new_uuid7()}
                        response = client.post(path, json=body, headers=headers)
                        assert response.status_code == 201, response.text
                        assert response.headers["X-Request-ID"] == headers["X-Request-ID"]
                        assert response.headers["X-Correlation-Id"] == headers["X-Correlation-Id"]
                        assert len(response.headers.get_list("X-Correlation-Id")) == 1
                        return response.json(), headers

                    with ThreadPoolExecutor(max_workers=8) as workers:
                        results = list(workers.map(quote, range(8)))
                    assert len({result["quoteId"] for result, _ in results}) == 8
                    original, headers = results[0]
                    headers = {**headers, "X-Request-ID": new_uuid7(), "X-Correlation-Id": "corr-" + new_uuid7()}
                    replay = client.post(path, json=body, headers=headers)
                    assert replay.status_code == 201 and replay.json() == original
                    assert replay.headers["X-Request-ID"] == headers["X-Request-ID"]
                    assert replay.headers["X-Correlation-Id"] == headers["X-Correlation-Id"]
                    assert client.get(path + "/" + original["quoteId"]).json() == original
                    assert client.post(path, json={**body, "seatClass": "FIRST_CLASS"}, headers=headers).status_code == 422
                    with pool.connection() as conn:
                        conn.execute("ALTER TABLE outbox ADD CONSTRAINT reject_quote CHECK(envelope->>'eventType'<>'FareQuoteComputed') NOT VALID")
                    failed = client.post(path, json=body, headers={"Idempotency-Key": new_uuid7()})
                    assert failed.status_code == 500
                    assert client.get("/readyz").status_code == 200
                with pool.connection() as conn:
                    for table in ("fare_quote_snapshots", "fare_quote_segment_links", "outbox", "idempotency_records"):
                        assert conn.execute(f"SELECT count(*) FROM {table}").fetchone()[0] == 8
                starts = [entry for entry in traces if entry[0] == "http.request.start"]
                assert len(starts) == 13
                assert len({id(entry[2]) for entry in starts}) == 13
                assert all(entry[2] is not None and entry[2].connection is None for entry in starts)
                assert any(entry[0] == "http.request.error" for entry in traces)
                assert _UNIT_OF_WORK.get() is None
                spans = exporter.get_finished_spans()
                assert len(spans) == 13
                assert all(span.attributes["request_id"] and span.attributes["correlation_id"] for span in spans)
                assert sum(span.attributes.get("http.response.status_code") == 201 for span in spans) == 9
        finally:
            provider.shutdown()
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
