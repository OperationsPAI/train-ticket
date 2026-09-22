import os
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg_pool import ConnectionPool

from identity_verification.adapters.storage.postgres import PostgresIdentityVerificationStore
from identity_verification.application.service import IdentityVerificationService


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="TEST_DATABASE_URL is required")
def test_concurrent_registration_returns_one_durable_credential():
    url = os.environ["TEST_DATABASE_URL"]
    schema = "credential_test_" + uuid4().hex
    with psycopg.connect(url, autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(url, max_size=8, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    for migration in sorted((Path(__file__).parents[1] / "migrations").glob("*.sql")):
                        conn.execute(migration.read_text())
                service = IdentityVerificationService(PostgresIdentityVerificationStore(pool))
                barrier = threading.Barrier(8)
                traveler = "tvl-" + uuid4().hex
                data = {"travelerId": traveler, "profileSnapshotVersion": "v1", "documentType": "ID_CARD",
                        "maskedDocumentNo": "11***********5", "documentHash": uuid4().hex + "-5",
                        "canonicalNameHash": uuid4().hex}

                def register(index):
                    barrier.wait(timeout=5)
                    return service.register_credential(data, str(uuid4()), None)

                with ThreadPoolExecutor(max_workers=8) as workers:
                    results = list(workers.map(register, range(8)))
                assert len({result["credentialRecordId"] for result in results}) == 1
                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM credential_record_snapshots").fetchone()[0] == 1
                    assert conn.execute("SELECT count(*) FROM outbox").fetchone()[0] == 1
                credential = service.store.get_credential(results[0]["credentialRecordId"])
                verification = {"travelerId": traveler, "credentialRecordId": credential.credentialRecordId,
                                "materialFingerprint": credential.materialFingerprint,
                                "purpose": "ORDER_CREATION", "simPolicyVersion": "sim-tail-v1"}

                def verify(index):
                    barrier.wait(timeout=5)
                    return service.start_verification_case(verification, str(uuid4()), None)

                with ThreadPoolExecutor(max_workers=8) as workers:
                    cases = list(workers.map(verify, range(8)))
                assert len({case["verificationCaseId"] for case in cases}) == 1
                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM verification_case_snapshots").fetchone()[0] == 1
                now = datetime.now(UTC)
                certificate = {"travelerId": traveler, "eligibilityType": "STUDENT",
                               "credentialRecordId": credential.credentialRecordId,
                               "validFrom": (now - timedelta(days=1)).isoformat(),
                               "validUntil": (now + timedelta(days=365)).isoformat(),
                               "policyYear": str(now.year), "policyVersion": "v1", "annualUsageLimit": 10,
                               "applicableProductCodes": ["rail-eligible"], "certificateHash": uuid4().hex,
                               "evidenceHash": uuid4().hex}

                def register_certificate(index):
                    barrier.wait(timeout=5)
                    return service.register_certificate(certificate, str(uuid4()), None)

                with pool.connection() as conn:
                    before = conn.execute("SELECT count(*) FROM outbox").fetchone()[0]
                with ThreadPoolExecutor(max_workers=8) as workers:
                    certificates = list(workers.map(register_certificate, range(8)))
                assert len({cert["eligibilityCertificateId"] for cert in certificates}) == 1
                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM eligibility_certificate_snapshots").fetchone()[0] == 1
                    assert conn.execute("SELECT count(*) FROM outbox").fetchone()[0] == before + 2
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
