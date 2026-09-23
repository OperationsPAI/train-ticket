import hashlib
import json
import os
from datetime import datetime, timedelta
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg import sql
from psycopg.types.json import Jsonb


ROOT = Path(__file__).resolve().parents[2]
INDEX = "idx_outbox_published_at"
SERVICES = (
    ("booking-orchestration", "004_outbox_retention_index.sql"),
    ("finance-settlement", "008_outbox_retention_index.sql"),
    ("journey-order", "005_outbox_retention_index.sql"),
    ("offer-management", "006_outbox_retention_index.sql"),
)
PREDICATE = "published_at IS NOT NULL AND published_at < now() - interval '30 seconds'"
DELETE = ("DELETE FROM outbox WHERE ctid IN (SELECT ctid FROM outbox WHERE "
          + PREDICATE + " LIMIT %s FOR UPDATE SKIP LOCKED)")
REPLAY_DELETE = DELETE.replace("now()", "%s::timestamptz")


def test_runtime_sql_and_budget():
    java = (ROOT / "platform/java-kit/src/main/java/com/trainticket/platformkit/persistence/OutboxRelay.java").read_text()
    typescript = (ROOT / "platform/ts-kit/src/storage.ts").read_text()
    assert ('"DELETE FROM outbox WHERE ctid IN (SELECT ctid FROM outbox "\n'
            '            + "WHERE ' + PREDICATE + ' "\n'
            '            + "LIMIT ? FOR UPDATE SKIP LOCKED)"') in java
    assert " ".join(DELETE.replace("%s", "$1").split()) in " ".join(typescript.split())
    for source in (java, typescript):
        assert "CLEANUP_BATCH_SIZE = 1_000;" in source
        assert "CLEANUP_MAX_BATCHES = 1;" in source


def connect():
    connection = psycopg.connect(os.environ["OUTBOX_RETENTION_TEST_DATABASE_URL"], autocommit=True)
    assert connection.info.host in ("localhost", "127.0.0.1", "::1"), "Local PostgreSQL is required"
    connection.execute("SET statement_timeout = '30s'")
    connection.execute("SET lock_timeout = '2s'")
    return connection


def nodes(plan):
    yield plan
    for child in plan.get("Plans", []):
        yield from nodes(child)


def explain(connection, query, params):
    connection.execute("SAVEPOINT explain_only")
    result = connection.execute("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + query, params).fetchone()[0][0]
    connection.execute("ROLLBACK TO SAVEPOINT explain_only")
    connection.execute("RELEASE SAVEPOINT explain_only")
    return result


def index_definition(connection):
    return connection.execute("""
        SELECT i.indisvalid, i.indisready, am.amname,
               pg_get_indexdef(i.indexrelid, 1, true),
               pg_get_expr(i.indpred, i.indrelid), i.indnkeyatts, i.indnatts,
               i.indisunique
        FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        JOIN pg_am am ON am.oid = c.relam
        WHERE i.indexrelid = %s::regclass
    """, (INDEX,)).fetchone()


def deleted(connection, query, params):
    return {row[0] for row in connection.execute(query + " RETURNING event_id", params)}


def drain(connection, query, params, expected):
    remaining = set(expected)
    batches = []
    while True:
        removed = deleted(connection, query, params)
        assert removed <= remaining
        assert len(removed) == min(1000, len(remaining))
        batches.append(len(removed))
        remaining -= removed
        if len(removed) < 1000:
            break
    assert not remaining
    return batches


@pytest.mark.parametrize("service,migration", SERVICES)
def test_recorded_retention(service, migration):
    raw = (Path(os.environ["OUTBOX_RETENTION_RECORDINGS"]) / f"{service.replace('-', '_')}.json").read_bytes()
    recording = json.loads(raw)
    assert recording["database"] == service.replace("-", "_")
    assert recording["read_only"] == "on"
    rows = recording["rows"]
    published = sorted(datetime.fromisoformat(row["published_at"]) for row in rows if row["published_at"])
    assert len(published) > 100, "Recorded events must cover a selective expiry range"
    unpublished = {row["event_id"] for row in rows if row["published_at"] is None}
    boundary = published[min(100, len(published) // 20)]
    replay_at = boundary + timedelta(seconds=30)
    eligible = {row["event_id"] for row in rows
                if row["published_at"] and datetime.fromisoformat(row["published_at"]) < boundary}
    equal_boundary = {row["event_id"] for row in rows if row["published_at"]
                      and datetime.fromisoformat(row["published_at"]) == boundary}
    assert 0 < len(eligible) < 1000 and equal_boundary
    assert any(row["published_at"] and datetime.fromisoformat(row["published_at"]) > boundary for row in rows)
    schema = "outbox_retention_" + uuid4().hex
    report = {"service": service, "recording_sha256": hashlib.sha256(raw).hexdigest(),
              "captured_at": recording["captured_at"], "recorded_rows": len(rows),
              "unpublished_rows": len(unpublished), "replay_at": replay_at.isoformat(),
              "eligible_rows": len(eligible), "boundary_rows": len(equal_boundary), "plans": {}}
    migrations = ROOT / "services" / service / "migrations"
    migration_sql = (migrations / migration).read_text()
    with connect() as connection:
        connection.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
        try:
            connection.execute(sql.SQL("SET search_path TO {}").format(sql.Identifier(schema)))
            with connection.transaction():
                for path in sorted(migrations.glob("*.sql")):
                    if path.name != migration:
                        connection.execute(path.read_text())
            with connection.cursor().copy("COPY outbox (seq, event_id, stream, envelope, created_at, published_at) FROM STDIN") as copy:
                for row in rows:
                    copy.write_row((row["seq"], row["event_id"], row["stream"], Jsonb(row["envelope"]),
                                    row["created_at"], row["published_at"]))
            connection.execute("ANALYZE outbox")
            assert connection.execute("SHOW enable_seqscan").fetchone()[0] == "on"
            report["server_version"] = connection.execute("SHOW server_version").fetchone()[0]
            report["heap_pages"] = connection.execute("SELECT pg_relation_size('outbox') / current_setting('block_size')::int").fetchone()[0]
            for stage in ("before", "after"):
                if stage == "after":
                    with connection.transaction():
                        connection.execute(migration_sql)
                        connection.execute(migration_sql)
                    assert index_definition(connection) == (True, True, "btree", "published_at",
                                                            "(published_at IS NOT NULL)", 1, 1, False)
                with connection.transaction(force_rollback=True):
                    plan = explain(connection, REPLAY_DELETE, (replay_at, 1000))
                    report["plans"][stage] = plan
                    scan_nodes = list(nodes(plan["Plan"]))
                    if stage == "before":
                        assert any(node["Node Type"] == "Seq Scan" and node.get("Relation Name") == "outbox" for node in scan_nodes)
                    else:
                        assert not any(node["Node Type"] == "Seq Scan" and node.get("Relation Name") == "outbox" for node in scan_nodes)
                        scans = [node for node in scan_nodes if node.get("Index Name") == INDEX]
                        assert scans and all("published_at <" in node["Index Cond"] for node in scans)
                        assert sum(node["Actual Rows"] * node["Actual Loops"] for node in scans) == len(eligible)
                        assert all(node.get("Rows Removed by Filter", 0) == 0 for node in scans)
                    assert deleted(connection, REPLAY_DELETE, (replay_at, 1000)) == eligible
                    assert deleted(connection, REPLAY_DELETE, (replay_at, 1000)) == set()
                    survivors = {row[0] for row in connection.execute("SELECT event_id FROM outbox")}
                    assert survivors == {row["event_id"] for row in rows} - eligible
                    assert unpublished | equal_boundary <= survivors
                with connection.transaction(force_rollback=True):
                    empty_plan = explain(connection, REPLAY_DELETE, (published[0] + timedelta(seconds=30), 1000))
                    report["plans"][stage + "_empty_range"] = empty_plan
                    if stage == "after":
                        empty_scans = [node for node in nodes(empty_plan["Plan"]) if node.get("Index Name") == INDEX]
                        assert empty_scans and all(node["Actual Rows"] == 0 for node in empty_scans)
                        assert not any(node["Node Type"] == "Seq Scan" for node in nodes(empty_plan["Plan"]))
                    backlog_at = published[-1] + timedelta(seconds=31)
                    all_published = {row["event_id"] for row in rows if row["published_at"]}
                    report[stage + "_backlog_batches"] = drain(connection, REPLAY_DELETE, (backlog_at, 1000), all_published)
                    assert {row[0] for row in connection.execute("SELECT event_id FROM outbox")} == unpublished
                with connection.transaction(force_rollback=True):
                    expected = {row[0] for row in connection.execute("SELECT event_id FROM outbox WHERE " + PREDICATE)}
                    report["plans"][stage + "_runtime_now"] = explain(connection, DELETE, (1000,))
                    report[stage + "_runtime_batches"] = drain(connection, DELETE, (1000,), expected)
                with connect() as locker:
                    locker.execute(sql.SQL("SET search_path TO {}").format(sql.Identifier(schema)))
                    with locker.transaction(force_rollback=True):
                        locked = set(sorted(eligible)[:7])
                        locker.execute("SELECT event_id FROM outbox WHERE event_id = ANY(%s) FOR UPDATE", (list(locked),)).fetchall()
                        with connection.transaction(force_rollback=True):
                            assert deleted(connection, REPLAY_DELETE, (replay_at, 1000)) == eligible - locked
                    with connection.transaction(force_rollback=True):
                        assert deleted(connection, REPLAY_DELETE, (replay_at, 1000)) == eligible
            assert report["before_backlog_batches"] == report["after_backlog_batches"]
            connection.execute(sql.SQL("DROP INDEX {}").format(sql.Identifier(INDEX)))
            connection.execute(migration_sql.replace("CREATE INDEX IF NOT EXISTS", "CREATE INDEX CONCURRENTLY"))
            online_definition = index_definition(connection)
            with connection.transaction():
                connection.execute(migration_sql)
            assert index_definition(connection) == online_definition
            assert online_definition == (True, True, "btree", "published_at", "(published_at IS NOT NULL)", 1, 1, False)
            report["online_index_valid"] = True
            report["restored_rows"] = connection.execute("SELECT count(*) FROM outbox").fetchone()[0]
            assert report["restored_rows"] == len(rows)
            if os.environ.get("OUTBOX_RETENTION_RESULTS"):
                output = Path(os.environ["OUTBOX_RETENTION_RESULTS"])
                output.mkdir(parents=True, exist_ok=True)
                (output / f"{service}.json").write_text(json.dumps(report, indent=2) + "\n")
            print(json.dumps({key: value for key, value in report.items() if key != "plans"}))
        finally:
            connection.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))
