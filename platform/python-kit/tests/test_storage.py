from __future__ import annotations

import json
from typing import Any

import pytest

from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.storage import OptimisticConcurrencyError, OutboxAppender, SnapshotRepository


class FakeCursor:
    def __init__(self, row=None):
        self._row = row

    def fetchone(self):
        return self._row


class FakeConn:
    def __init__(self, rows):
        self.rows = list(rows)
        self.commands = []

    def execute(self, sql, params=()):
        self.commands.append((sql, params))
        if self.rows:
            return FakeCursor(self.rows.pop(0))
        return FakeCursor(None)


def test_snapshot_save_raises_on_zero_row_optimistic_update() -> None:
    repo = SnapshotRepository("fare_rule_set_snapshots")
    conn = FakeConn([None])

    with pytest.raises(OptimisticConcurrencyError):
        repo.save(conn, "frs-1", {"status": "published"}, expected_version=3)

    sql, params = conn.commands[0]
    assert "WHERE id = %s AND version = %s" in sql
    assert params[1:] == ("frs-1", 3)


def test_outbox_appender_stores_exact_envelope_json() -> None:
    conn = FakeConn([])
    envelope = EventEnvelope(eventId="evt-1", eventType="ThingHappened", producer="fare-pricing", correlationId="corr-1", payload={"minorUnits": 123})

    OutboxAppender().append(conn, envelope)

    sql, params = conn.commands[0]
    assert "INSERT INTO outbox" in sql
    assert params[0] == "evt-1"
    assert params[1] == "events:fare-pricing"
    payload: Any = params[2]
    if isinstance(payload, str):
        stored = json.loads(payload)
    else:
        stored = payload.obj if hasattr(payload, "obj") else payload
    assert stored == envelope.to_json_dict()


def test_snapshot_save_insert_conflict_raises_without_upsert() -> None:
    repo = SnapshotRepository("fare_quote_snapshots")
    conn = FakeConn([None])

    with pytest.raises(OptimisticConcurrencyError):
        repo.save(conn, "fq-1", {"status": "quoted"})

    sql, _ = conn.commands[0]
    assert "ON CONFLICT (id) DO NOTHING" in sql
    assert "DO UPDATE" not in sql


def test_outbox_relay_uses_envelope_stream_field() -> None:
    from train_ticket_platform.storage import OutboxRelay

    class RedisFake:
        def __init__(self) -> None:
            self.calls = []

        def xadd(self, stream, fields, **kwargs):
            self.calls.append((stream, fields, kwargs))

    class Cursor:
        def fetchall(self):
            return [(1, "events:fare-pricing", {"eventId": "evt-1"})]

    class Conn(FakeConn):
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb) -> None:
            return None

        def execute(self, sql, params=()):
            self.commands.append((sql, params))
            if sql.startswith("SELECT seq"):
                return Cursor()
            return FakeCursor(None)

        def commit(self) -> None:
            self.commands.append(("COMMIT", ()))

    class Pool:
        def __init__(self, conn) -> None:
            self.conn = conn

        def connection(self):
            return self.conn

    redis = RedisFake()
    OutboxRelay(Pool(Conn([])), redis_client=redis).relay_once()

    assert redis.calls[0][1].keys() == {"envelope"}


class SweepConn(FakeConn):
    """Records the sweep statements and reports an exhausted table."""

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        return None

    def execute(self, sql, params=()):
        self.commands.append((sql, params))
        cursor = FakeCursor(None)
        cursor.rowcount = 0
        return cursor

    def commit(self) -> None:
        return None


class SweepPool:
    def __init__(self, conn) -> None:
        self.conn = conn

    def connection(self):
        return self.conn


def sweep_statements() -> list[str]:
    from train_ticket_platform.storage import OutboxRelay

    conn = SweepConn([])
    OutboxRelay(SweepPool(conn), redis_client=object())._cleanup()
    return [sql for sql, _ in conn.commands]


def test_cleanup_sweeps_all_three_platform_tables() -> None:
    statements = sweep_statements()
    for table in ("outbox", "processed_events", "idempotency_records"):
        assert any(
            f"DELETE FROM {table} WHERE ctid IN" in sql for sql in statements
        ), f"no batched ctid sweep for {table}; statements: {statements}"


def test_every_sweep_claims_its_rows_with_skip_locked() -> None:
    # This service runs four uvicorn workers, each with its own relay thread.
    # Without SKIP LOCKED two sweeps pick overlapping rows and lock them in
    # opposite orders: the deployed cluster logged 76 deadlocks in one window,
    # every one of them two retention statements waiting on each other.
    for sql in sweep_statements():
        assert "FOR UPDATE SKIP LOCKED" in sql, (
            f"concurrent sweepers would contend for the same rows: {sql}"
        )
