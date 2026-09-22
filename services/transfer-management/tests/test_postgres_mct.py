import os
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg_pool import ConnectionPool

from transfer_management.adapters.storage.postgres import PostgresTransferManagementStore
from transfer_management.domain import MctRule, MctRuleStatus, NodeType, TransferCategory


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="TEST_DATABASE_URL is required")
def test_mct_selection_uses_validity_and_highest_version():
    database_url = os.environ["TEST_DATABASE_URL"]
    schema = "mct_test_" + uuid4().hex
    with psycopg.connect(database_url, autocommit=True) as admin:
        admin.execute(psycopg.sql.SQL("CREATE SCHEMA {}").format(psycopg.sql.Identifier(schema)))
        try:
            with ConnectionPool(database_url, kwargs={"options": f"-c search_path={schema}"}) as pool:
                with pool.connection() as conn:
                    conn.execute("CREATE TABLE mct_rule_snapshots(id text PRIMARY KEY,version bigint,data jsonb,updated_at timestamptz DEFAULT now())")
                    conn.execute(Path("services/transfer-management/migrations/005_mct_lookup.sql").read_text())
                store = PostgresTransferManagementStore(pool)
                now = datetime.now(UTC)
                node_type = next(iter(NodeType))
                category = next(iter(TransferCategory))
                for version, expiry in [(1, now + timedelta(days=1)), (2, now + timedelta(days=1)), (3, now - timedelta(seconds=1))]:
                    store.save_mct_rule(MctRule(f"mct-{version}", version, MctRuleStatus.PUBLISHED, node_type, node_type, category, 15, {}, now - timedelta(days=1), expiry))
                selected = store.find_published_mct_rule(node_type, node_type, category, now)
                assert selected is not None and selected.mctRuleId == "mct-2"
        finally:
            admin.execute(psycopg.sql.SQL("DROP SCHEMA {} CASCADE").format(psycopg.sql.Identifier(schema)))
