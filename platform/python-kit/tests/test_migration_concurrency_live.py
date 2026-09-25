from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg_pool import ConnectionPool

from train_ticket_platform.storage import run_migrations


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="requires PostgreSQL via TEST_DATABASE_URL")
def test_concurrent_migration_runners_serialize(tmp_path: Path) -> None:
    database_url = os.environ["TEST_DATABASE_URL"]
    schema = "migration_lock_" + uuid4().hex
    migration = tmp_path / "001_probe.sql"
    migration.write_text(
        "SELECT pg_sleep(0.2); "
        "CREATE TABLE migration_probe(id text PRIMARY KEY); "
        "INSERT INTO migration_probe(id) VALUES ('applied-once');",
        encoding="utf-8",
    )

    with psycopg.connect(database_url, autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(
                database_url,
                min_size=1,
                max_size=8,
                kwargs={"options": f"-c search_path={schema}"},
            ) as pool:
                with ThreadPoolExecutor(max_workers=8) as workers:
                    list(workers.map(lambda _: run_migrations(pool, tmp_path), range(8)))

                with pool.connection() as conn:
                    assert conn.execute("SELECT count(*) FROM schema_migrations").fetchone()[0] == 1
                    assert conn.execute("SELECT count(*) FROM migration_probe").fetchone()[0] == 1
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
