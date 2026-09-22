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
from fare_pricing.web.handlers import _fare_quote_to_response


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_QUOTED_ELIGIBILITY"), reason="requires PostgreSQL and recorded fare policy and credentials")
def test_mixed_travelers_reserve_only_the_certificate_used_by_the_quote():
    from identity_verification.adapters.storage.postgres import PostgresIdentityVerificationStore
    from identity_verification.application.service import IdentityVerificationService
    from identity_verification.domain import DomainError
    source = json.loads(Path(os.environ["TEST_QUOTED_ELIGIBILITY"]).read_text())
    certificate, credential, adult = source["certificate"], source["credential"], source["adult"]
    root = Path(__file__).parents[2]
    fare_schema, identity_schema = "quoted_fare_" + uuid4().hex, "quoted_identity_" + uuid4().hex
    with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as admin:
        for schema in (fare_schema, identity_schema):
            admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(os.environ["TEST_DATABASE_URL"], max_size=4, kwargs={"options": f"-c search_path={fare_schema}"}) as fare_pool, ConnectionPool(os.environ["TEST_DATABASE_URL"], max_size=4, kwargs={"options": f"-c search_path={identity_schema}"}) as identity_pool:
                for pool, service in ((fare_pool, "fare-pricing"), (identity_pool, "identity-verification")):
                    with pool.connection() as conn:
                        for migration in sorted((root / service / "migrations").glob("*.sql")):
                            conn.execute(migration.read_text())
                with fare_pool.connection() as conn:
                    conn.execute("INSERT INTO fare_rule_set_snapshots(id,version,data) VALUES (%s,1,%s::jsonb)", (source["rule"]["ruleSetId"], json.dumps(source["rule"])))
                with identity_pool.connection() as conn:
                    for item in (credential, adult):
                        conn.execute("INSERT INTO credential_record_snapshots(id,version,data) VALUES (%s,1,%s::jsonb)", (item["credentialRecordId"], json.dumps(item)))
                    conn.execute("INSERT INTO eligibility_certificate_snapshots(id,version,data) VALUES (%s,%s,%s::jsonb)", (certificate["eligibilityCertificateId"], certificate["aggregateVersion"], json.dumps(certificate)))
                fare_store = PostgresFarePricingStore(fare_pool)
                with fare_store.transaction():
                    fare_store.upsert_eligibility_certificate(_certificate_from_json({**certificate, "certificateStatus": certificate["status"]}))
                travelers = [adult["travelerId"], credential["travelerId"]]
                with fare_store.transaction():
                    quote = FarePricingService(fare_store).compute_fare_quote("fq-" + uuid4().hex, "input-" + uuid4().hex, travelers, source["rule"]["channel"], source["rule"]["ruleSetId"], "CNY",
                        segment_refs=[source["purchase"]["seg"]], departure_time=datetime.fromisoformat(source["purchase"]["journey_date"]).replace(tzinfo=UTC))
                restored = PostgresFarePricingStore(fare_pool).get_quote(quote.quote_id)
                response = _fare_quote_to_response(restored)
                assert response["productCode"] == source["rule"]["productCode"]
                usages = response["eligibilityUsages"]
                assert usages == [{"travelerId": credential["travelerId"], "eligibilityType": certificate["eligibilityType"], "eligibilityCertificateId": certificate["eligibilityCertificateId"]}]
                assert any(item["explanation"]["parameters"].get("eligibilityType") == certificate["eligibilityType"] and item["amount"]["minorUnits"] > 0 for item in response["breakdown"]["discounts"])
                identity = IdentityVerificationService(PostgresIdentityVerificationStore(identity_pool))
                request = {"accountId": source["purchase"]["account"], "travelerRefs": travelers, "segmentRefs": [source["purchase"]["seg"]], "journeyDate": source["purchase"]["journey_date"],
                           "productCode": response["productCode"], "eligibilityUsages": usages, "orderIntentId": "oint-" + uuid4().hex, "limitPolicyVersion": "limit-v1"}
                corr = "corr-" + uuid4().hex
                with pytest.raises(DomainError, match="quoted traveler"):
                    identity.pre_order_check({**request, "eligibilityUsages": [{**usages[0], "travelerId": adult["travelerId"]}]}, corr, None)
                first, _ = identity.pre_order_check(request, corr, None)
                assert first["result"] == "PASS"
                replay, _ = identity.pre_order_check(request, corr, None)
                assert replay["preOrderCheckId"] == first["preOrderCheckId"]
                assert identity.store.get_certificate(certificate["eligibilityCertificateId"]).annualUsageReserved == 1
                for _ in range(2):
                    identity.confirm_pre_order_check(first["preOrderCheckId"], source["purchase"]["order"], corr, None)
                second, _ = identity.pre_order_check({**request, "orderIntentId": "oint-" + uuid4().hex}, corr, None)
                for _ in range(2):
                    identity.release_pre_order_check(second["preOrderCheckId"], "ORDER_CANCELLED", corr, None)
                final = identity.store.get_certificate(certificate["eligibilityCertificateId"])
                assert final.annualUsageReserved == 0 and final.annualUsageConfirmed == 1
                with identity_pool.connection() as conn:
                    conn.execute("UPDATE eligibility_certificate_snapshots SET data=jsonb_set(data,'{annualUsageConfirmed}',data->'annualUsageLimit') WHERE id=%s", (certificate["eligibilityCertificateId"],))
                exhausted, _ = identity.pre_order_check({**request, "orderIntentId": "oint-" + uuid4().hex}, corr, None)
                assert exhausted["result"] == "REJECT"
                assert identity.store.get_certificate(certificate["eligibilityCertificateId"]).annualUsageReserved == 0
        finally:
            for schema in (fare_schema, identity_schema):
                admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
