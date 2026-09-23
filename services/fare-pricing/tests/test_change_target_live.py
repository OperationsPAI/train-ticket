import json
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient
from psycopg_pool import ConnectionPool

from fare_pricing.api import create_app
from fare_pricing.adapters.storage.postgres import PostgresFarePricingStore
from train_ticket_platform.ids import new_uuid7
from train_ticket_platform.storage import PostgresIdempotencyStore


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_QUOTED_ELIGIBILITY"), reason="requires PostgreSQL and recorded pricing inputs")
def test_change_uses_the_selected_target_quote_and_validates_travelers(monkeypatch):
    source = json.loads(Path(os.environ["TEST_QUOTED_ELIGIBILITY"]).read_text())
    schema = "change_target_" + uuid4().hex
    monkeypatch.setenv("FARE_PRICING_DISABLE_SUBSCRIBER", "1")
    with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(os.environ["TEST_DATABASE_URL"], min_size=1, max_size=4, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    for migration in sorted((Path(__file__).parents[1] / "migrations").glob("*.sql")):
                        conn.execute(migration.read_text())
                    conn.execute("INSERT INTO fare_rule_set_snapshots(id,version,data) VALUES (%s,1,%s::jsonb)", (source["rule"]["ruleSetId"], json.dumps(source["rule"])))
                app = create_app(store=PostgresFarePricingStore(pool), idempotency_store=PostgresIdempotencyStore(pool))
                with TestClient(app, raise_server_exceptions=False) as client:
                    body = {"travelerRefs": [source["adult"]["travelerId"]], "channel": source["rule"]["channel"], "productCode": source["rule"]["productCode"],
                            "segmentRefs": [source["purchase"]["seg"]], "departureTime": source["purchase"]["journey_date"] + "T00:00:00Z"}
                    def post(path, data):
                        return client.post(path, json=data, headers={"Idempotency-Key": new_uuid7()})
                    original = post("/api/v1/fare-quotes", body)
                    target = post("/api/v1/fare-quotes", {**body, "seatClass": "FIRST_CLASS"})
                    other = post("/api/v1/fare-quotes", {**body, "travelerRefs": [source["credential"]["travelerId"]]})
                    assert original.status_code == target.status_code == other.status_code == 201
                    original, target, other = original.json(), target.json(), other.json()
                    request = {"purpose": "CHANGE", "originalQuoteId": original["quoteId"], "targetQuoteId": target["quoteId"],
                               "entitlementIds": [source["purchase"]["entitlement"]], "journeyOrderId": source["purchase"]["order"], "segmentRefs": body["segmentRefs"]}
                    response = post("/api/v1/adjustment-quotes", request)
                    assert response.status_code == 201, response.text
                    result = response.json()
                    difference = target["breakdown"]["total"]["minorUnits"] - original["breakdown"]["total"]["minorUnits"]
                    assert difference > 0
                    assert result["targetQuoteId"] == target["quoteId"] and result["originalQuoteId"] == original["quoteId"]
                    assert result["fareDifference"]["minorUnits"] == difference
                    assert result["amountDue"]["minorUnits"] == difference + result["changeFee"]["minorUnits"]
                    lower = post("/api/v1/adjustment-quotes", {**request, "originalQuoteId": target["quoteId"], "targetQuoteId": original["quoteId"]})
                    assert lower.status_code == 201, lower.text
                    assert lower.json()["refundableAmount"]["minorUnits"] == difference - result["changeFee"]["minorUnits"]
                    assert post("/api/v1/adjustment-quotes", {key: value for key, value in request.items() if key != "targetQuoteId"}).status_code in (400, 422)
                    assert post("/api/v1/adjustment-quotes", {**request, "targetQuoteId": other["quoteId"]}).status_code == 422
                    with pool.connection() as conn:
                        assert conn.execute("SELECT count(*) FROM adjustment_quote_snapshots").fetchone()[0] == 2
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
