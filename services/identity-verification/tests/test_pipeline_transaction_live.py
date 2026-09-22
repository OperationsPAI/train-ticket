import json
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg_pool import ConnectionPool

from identity_verification.adapters.storage.postgres import PostgresIdentityVerificationStore
from identity_verification.application.service import IdentityVerificationService


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL") or not os.getenv("TEST_IDENTITY_HTTP_SOURCE"), reason="requires PostgreSQL and recorded credential")
def test_pipeline_outbox_failure_rolls_back_credential_and_connection_remains_usable():
    credential = json.loads(Path(os.environ["TEST_IDENTITY_HTTP_SOURCE"]).read_text())
    schema = "identity_pipeline_" + uuid4().hex
    body = {key: credential[key] for key in ("travelerId", "profileSnapshotVersion", "documentType", "maskedDocumentNo", "documentHash", "canonicalNameHash", "birthDateHash", "validUntil", "evidenceHash")}
    with psycopg.connect(os.environ["TEST_DATABASE_URL"], autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(os.environ["TEST_DATABASE_URL"], min_size=1, max_size=1, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    for migration in sorted((Path(__file__).parents[1] / "migrations").glob("*.sql")):
                        conn.execute(migration.read_text())
                    conn.execute("ALTER TABLE outbox ADD CONSTRAINT reject_credential CHECK(envelope->>'eventType'<>'CredentialRegistered')")
                service = IdentityVerificationService(PostgresIdentityVerificationStore(pool))
                with pytest.raises(psycopg.errors.CheckViolation):
                    service.register_credential(body, "corr-" + uuid4().hex, None)
                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM credential_record_snapshots").fetchone()[0] == 0
                    assert conn.execute("SELECT count(*) FROM outbox").fetchone()[0] == 0
                    assert conn.pgconn.pipeline_status == psycopg.pq.PipelineStatus.OFF
                    conn.execute("ALTER TABLE outbox DROP CONSTRAINT reject_credential")
                created = service.register_credential(body, "corr-" + uuid4().hex, None)
                replay = service.register_credential(body, "corr-" + uuid4().hex, None)
                assert created == replay
                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM credential_record_snapshots").fetchone()[0] == 1
                    assert conn.execute("SELECT count(*) FROM outbox").fetchone()[0] == 1
                    assert conn.pgconn.pipeline_status == psycopg.pq.PipelineStatus.OFF
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
