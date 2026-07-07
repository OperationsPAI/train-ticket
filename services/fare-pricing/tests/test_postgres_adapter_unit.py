from __future__ import annotations

from datetime import UTC, datetime, timedelta

from fare_pricing.adapters.storage.postgres import PostgresFarePricingStore, domain_to_json
from fare_pricing.domain import FareRule, FareRuleSet, Money, PriceExplanation, RuleKind, ValidityWindow
from fare_pricing.ports import EventEnvelope
from train_ticket_platform.storage import OutboxAppender


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

    store.get_rule_set(original.rule_set_id)
    store.save_rule_set(updated)

    update_sql, update_params = conn.commands[1]
    assert "UPDATE fare_rule_set_snapshots SET version = version + 1" in update_sql
    assert "WHERE id = %s AND version = %s" in update_sql
    assert update_params[1:] == (original.rule_set_id, 7)


def test_save_loaded_quote_conflict_raises_optimistic_concurrency() -> None:
    from fare_pricing.domain import calculate_fare_quote
    from train_ticket_platform.storage import OptimisticConcurrencyError

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

    loaded = store.get_quote(quote.quote_id)
    try:
        store.save_quote(loaded.expire(loaded.valid_until))
    except OptimisticConcurrencyError:
        pass
    else:  # pragma: no cover - assertion branch
        raise AssertionError("expected optimistic concurrency conflict")

    update_sql, update_params = conn.commands[1]
    assert "UPDATE fare_quote_snapshots SET version = version + 1" in update_sql
    assert update_params[1:] == (quote.quote_id, 3)
