import os
import threading
from concurrent.futures import ThreadPoolExecutor
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
                        "maskedDocumentNo": "11***********5", "documentHash": uuid4().hex,
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
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
