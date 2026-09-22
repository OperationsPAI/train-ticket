import json
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg_pool import ConnectionPool

from identity_verification.adapters.storage.postgres import PostgresIdentityVerificationStore
from identity_verification.application.service import IdentityVerificationService


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_ELIGIBILITY_SOURCE"), reason="requires PostgreSQL and recorded eligibility")
def test_distinct_orders_reserve_confirm_and_release_one_certificate_concurrently():
    source = json.loads(Path(os.environ["TEST_ELIGIBILITY_SOURCE"]).read_text())
    certificate, credential, purchase = source["certificate"], source["credential"], source["purchase"]
    assert certificate["annualUsageReserved"] == certificate["annualUsageConfirmed"] == 0
    schema = "eligibility_concurrent_" + uuid4().hex
    with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(os.environ["TEST_DATABASE_URL"], min_size=1, max_size=8, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    for migration in sorted((Path(__file__).parents[1] / "migrations").glob("*.sql")):
                        conn.execute(migration.read_text())
                    conn.execute("INSERT INTO credential_record_snapshots(id,version,data) VALUES (%s,1,%s::jsonb)", (credential["credentialRecordId"], json.dumps(credential)))
                    conn.execute("INSERT INTO eligibility_certificate_snapshots(id,version,data) VALUES (%s,%s,%s::jsonb)", (certificate["eligibilityCertificateId"], certificate["aggregateVersion"], json.dumps(certificate)))
                service = IdentityVerificationService(PostgresIdentityVerificationStore(pool))
                request = {"accountId": purchase["account"], "travelerRefs": [purchase["traveler"]], "segmentRefs": [purchase["seg"]],
                           "journeyDate": purchase["journey_date"], "productCode": certificate["applicableProductCodes"][0],
                           "requestedEligibilityTypes": [certificate["eligibilityType"], certificate["eligibilityType"]], "limitPolicyVersion": "limit-v1"}

                def reserve(index):
                    result, _ = service.pre_order_check({**request, "orderIntentId": "oint-" + uuid4().hex}, "corr-" + uuid4().hex, None)
                    assert result["result"] == "PASS"
                    return result["preOrderCheckId"]

                with ThreadPoolExecutor(max_workers=8) as workers:
                    checks = list(workers.map(reserve, range(16)))
                stored = service.store.get_certificate(certificate["eligibilityCertificateId"])
                assert stored.annualUsageReserved == 16
                assert len(set(checks)) == 16

                def finish(index):
                    if index % 2:
                        return service.confirm_pre_order_check(checks[index], purchase["order"], "corr-" + uuid4().hex, None)
                    return service.release_pre_order_check(checks[index], "ORDER_CANCELLED", "corr-" + uuid4().hex, None)

                with ThreadPoolExecutor(max_workers=8) as workers:
                    list(workers.map(finish, range(16)))
                    list(workers.map(finish, range(16)))
                stored = service.store.get_certificate(certificate["eligibilityCertificateId"])
                assert stored.annualUsageReserved == 0
                assert stored.annualUsageConfirmed == 8
                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='EligibilityUsageReserved'").fetchone()[0] == 16
                    assert conn.execute("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='EligibilityUsageConfirmed'").fetchone()[0] == 8
                    assert conn.execute("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='EligibilityUsageReleased'").fetchone()[0] == 8
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
