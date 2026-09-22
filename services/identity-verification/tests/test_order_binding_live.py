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
from identity_verification.domain import PreconditionFailed


@pytest.mark.skipif(not os.environ.get("TEST_BOUND_CHECK"),reason="requires real confirmed identity check and TEST_DATABASE_URL")
def test_confirmed_identity_check_remains_bound_to_its_order():
    source=json.loads(Path(os.environ["TEST_BOUND_CHECK"]).read_text())
    check=source["check"]
    orders={fact["journeyOrderId"] for fact in source["facts"]}
    assert len(orders)==1
    order=next(iter(orders))
    url=os.environ["TEST_DATABASE_URL"]
    schema="identity_binding_"+uuid4().hex
    with psycopg.connect(url,autocommit=True) as admin:
        admin.execute(f"CREATE SCHEMA {schema}")
        try:
            with ConnectionPool(url,min_size=1,max_size=4,kwargs={"options":f"-c search_path={schema}"}) as pool:
                with pool.connection() as connection:
                    for file in sorted((Path(__file__).parents[1]/"migrations").glob("*.sql")):
                        connection.execute(file.read_text())
                    connection.execute("INSERT INTO pre_order_check_snapshots(id,version,data) VALUES (%s,1,%s::jsonb)",(check["preOrderCheckId"],json.dumps(check)))
                    for fact in source["facts"]:
                        connection.execute("INSERT INTO purchase_limit_fact_snapshots(id,version,data) VALUES (%s,%s,%s::jsonb)",(fact["purchaseLimitFactId"],fact["aggregateVersion"],json.dumps(fact)))
                service=IdentityVerificationService(PostgresIdentityVerificationStore(pool))
                wrong="ord-"+uuid4().hex
                with pytest.raises(PreconditionFailed,match="different order"):
                    service.confirm_pre_order_check(check["preOrderCheckId"],wrong,"corr-"+uuid4().hex,None)
                with ThreadPoolExecutor(max_workers=4) as workers:
                    results=list(workers.map(lambda _:service.confirm_pre_order_check(check["preOrderCheckId"],order,"corr-"+uuid4().hex,None),range(4)))
                assert all(result["preOrderCheckId"]==check["preOrderCheckId"] for result in results)
                restarted=IdentityVerificationService(PostgresIdentityVerificationStore(pool))
                with pytest.raises(PreconditionFailed,match="different order"):
                    restarted.confirm_pre_order_check(check["preOrderCheckId"],wrong,"corr-"+uuid4().hex,None)
                with pool.connection() as connection:
                    assert connection.execute("SELECT data->>'journeyOrderId' FROM pre_order_check_snapshots").fetchone()[0]==order
                    assert connection.execute("SELECT count(*) FROM outbox").fetchone()[0]==0
        finally:admin.execute(f"DROP SCHEMA {schema} CASCADE")
