from __future__ import annotations

from contextvars import Context
from datetime import UTC, datetime, timedelta
import os
from pathlib import Path

import pytest

from fare_pricing.adapters.storage.postgres import PostgresFarePricingStore, domain_to_json
from fare_pricing.domain import FareRule, FareRuleSet, Money, PriceExplanation, RuleKind, ValidityWindow
from fare_pricing.ports import EventEnvelope
from train_ticket_platform.storage import (
    DatabaseConfig,
    DatabasePool,
    OptimisticConcurrencyError,
    OutboxAppender,
    ReadinessGate,
    run_migrations,
)


class FakeConn:
    def __init__(self) -> None:
        self.commands: list[tuple[str, tuple[object, ...]]] = []

    def execute(self, sql: str, params: tuple[object, ...] = ()):
        self.commands.append((sql, params))
        return self

    def fetchone(self):
        return None


def rule_set(rule_set_id: str, channel: str = "web") -> FareRuleSet:
    now = datetime(2026, 7, 7, tzinfo=UTC)
    return FareRuleSet(
        rule_set_id=rule_set_id,
        supplier_id="supplier",
        product_code="rail-standard",
        mode="rail",
        channel=channel,
        version="v1",
        effective_window=ValidityWindow(now - timedelta(days=1), now + timedelta(days=1)),
        rules=(FareRule("base", RuleKind.BASE_FARE, Money("1.00", "CNY"), PriceExplanation("fare.base")),),
    ).publish(now)


def test_rule_set_json_uses_contract_money_minor_units() -> None:
    payload = domain_to_json(rule_set("frs-1"))
    assert payload["rules"][0]["amount"] == {"currency": "CNY", "minorUnits": 100}
    assert payload["effectiveWindow"]["startsAt"].endswith("Z")


def test_outbox_can_be_appended_in_same_connection_as_state_change() -> None:
    conn = FakeConn()
    envelope = EventEnvelope(event_id="evt-atomic", event_type="FareRuleSetPublished", correlation_id="corr-1", payload={"ruleSetId": "frs-1"})

    conn.execute("UPDATE fare_rule_sets SET status = %s WHERE rule_set_id = %s", ("published", "frs-1"))
    OutboxAppender().append(conn, envelope)

    assert len(conn.commands) == 2
    assert "UPDATE fare_rule_sets" in conn.commands[0][0]
    assert "INSERT INTO outbox" in conn.commands[1][0]


class ReturningCursor:
    def __init__(self, row=None):
        self._row = row

    def fetchone(self):
        return self._row


class SnapshotFakeConn:
    def __init__(self, rows: list[object] | None = None) -> None:
        self.rows = list(rows or [])
        self.commands: list[tuple[str, tuple[object, ...]]] = []

    def execute(self, sql: str, params: tuple[object, ...] = ()):  # noqa: ANN201 - psycopg-compatible fake
        self.commands.append((sql, params))
        row = self.rows.pop(0) if self.rows else None
        return ReturningCursor(row)


class SingleConnectionPool:
    def __init__(self, conn: SnapshotFakeConn) -> None:
        self.conn = conn

    def connection(self):  # noqa: ANN201 - context-manager fake
        return self

    def __enter__(self) -> SnapshotFakeConn:
        return self.conn

    def __exit__(self, exc_type, exc, tb) -> None:  # noqa: ANN001
        return None


def test_save_loaded_rule_set_uses_snapshot_version_for_optimistic_update() -> None:
    original = rule_set("frs-versioned")
    updated = original.supersede()
    conn = SnapshotFakeConn(rows=[(7, domain_to_json(original)), (8,)])
    store = PostgresFarePricingStore(SingleConnectionPool(conn))

    with store.unit_of_work():
        store.get_rule_set(original.rule_set_id)
        store.save_rule_set(updated)

    update_sql, update_params = conn.commands[1]
    assert "UPDATE fare_rule_set_snapshots SET version = version + 1" in update_sql
    assert "WHERE id = %s AND version = %s" in update_sql
    assert update_params[1:] == (original.rule_set_id, 7)


def test_save_loaded_quote_conflict_raises_optimistic_concurrency() -> None:
    from fare_pricing.domain import calculate_fare_quote

    original = rule_set("frs-quote-conflict")
    quote = calculate_fare_quote(
        quote_id="fq-quote-conflict",
        input_hash="hash",
        traveler_refs=("traveler-1",),
        channel=original.channel,
        rule_set=original,
        requested_currency="CNY",
        quoted_at=datetime(2026, 7, 7, tzinfo=UTC),
        ttl=timedelta(minutes=15),
    )
    conn = SnapshotFakeConn(rows=[(3, domain_to_json(quote)), None])
    store = PostgresFarePricingStore(SingleConnectionPool(conn))

    try:
        with store.unit_of_work():
            loaded = store.get_quote(quote.quote_id)
            store.save_quote(loaded.expire(loaded.valid_until))
    except OptimisticConcurrencyError:
        pass
    else:  # pragma: no cover - assertion branch
        raise AssertionError("expected optimistic concurrency conflict")

    update_sql, update_params = conn.commands[1]
    assert "UPDATE fare_quote_snapshots SET version = version + 1" in update_sql
    assert update_params[1:] == (quote.quote_id, 3)


def test_interleaved_request_contexts_keep_loaded_versions_isolated() -> None:
    original = rule_set("frs-isolated")
    conn = SnapshotFakeConn(rows=[(5, domain_to_json(original)), (5, domain_to_json(original)), (6,), None])
    store = PostgresFarePricingStore(SingleConnectionPool(conn))

    first_context = Context()
    second_context = Context()
    first_uow = store.unit_of_work()
    second_uow = store.unit_of_work()
    first_context.run(first_uow.__enter__)
    second_context.run(second_uow.__enter__)
    try:
        first_loaded = first_context.run(store.get_rule_set, original.rule_set_id)
        second_loaded = second_context.run(store.get_rule_set, original.rule_set_id)
        assert first_loaded.rule_set_id == second_loaded.rule_set_id == original.rule_set_id

        first_context.run(store.save_rule_set, first_loaded.supersede())
        with pytest.raises(OptimisticConcurrencyError):
            second_context.run(store.save_rule_set, second_loaded.supersede())
    finally:
        second_context.run(second_uow.__exit__, None, None, None)
        first_context.run(first_uow.__exit__, None, None, None)

    first_update = conn.commands[2]
    second_update = conn.commands[3]
    assert first_update[1][1:] == (original.rule_set_id, 5)
    assert second_update[1][1:] == (original.rule_set_id, 5)


@pytest.mark.skipif(not os.getenv("DATABASE_URL"), reason="requires live Postgres via DATABASE_URL")
def test_real_postgres_two_transactions_detect_write_conflict() -> None:
    migrations_dir = Path(__file__).resolve().parents[1] / "migrations"
    pool = DatabasePool(DatabaseConfig(os.environ["DATABASE_URL"]), min_size=1, max_size=4)
    try:
        run_migrations(pool, migrations_dir, ReadinessGate())
        store = PostgresFarePricingStore(pool)
        aggregate = rule_set("frs-live-conflict")
        with pool.connection() as conn:
            with conn.transaction():
                conn.execute("DELETE FROM fare_rule_set_snapshots WHERE id = %s", (aggregate.rule_set_id,))
        store.save_rule_set(aggregate)

        first_context = Context()
        second_context = Context()
        first_transaction = store.transaction()
        second_transaction = store.transaction()
        first_context.run(first_transaction.__enter__)
        second_context.run(second_transaction.__enter__)
        first_open = True
        try:
            first_loaded = first_context.run(store.get_rule_set, aggregate.rule_set_id)
            second_loaded = second_context.run(store.get_rule_set, aggregate.rule_set_id)
            first_context.run(store.save_rule_set, first_loaded.supersede())
            first_context.run(first_transaction.__exit__, None, None, None)
            first_open = False
            with pytest.raises(OptimisticConcurrencyError):
                second_context.run(store.save_rule_set, second_loaded.supersede())
        finally:
            second_context.run(second_transaction.__exit__, None, None, None)
            if first_open:
                first_context.run(first_transaction.__exit__, None, None, None)
    finally:
        pool.close()
