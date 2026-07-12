from __future__ import annotations

import json
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import UTC, datetime
from typing import Any, Mapping

from risk_compliance.application import (
    AssessmentNotFoundError,
    BlockNotFoundError,
    FREQUENCY_WINDOW,
    IP_FREQUENCY_DECAY_HALF_LIFE,
    RiskAssessmentResult,
    RiskBlockApplied,
)
from risk_compliance.domain import RiskEvaluation, RiskSignal, RiskVerdict, RuleResult
from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.storage import OutboxAppender, ProcessedEventsGuard, SnapshotRepository


def _json_obj(data: Mapping[str, Any] | str) -> Mapping[str, Any]:
    return json.loads(data) if isinstance(data, str) else data


def _assessment_to_json(result: RiskAssessmentResult) -> dict[str, Any]:
    return {**result.to_event_payload()}


def _assessment_from_json(data: Mapping[str, Any] | str) -> RiskAssessmentResult:
    data = _json_obj(data)
    return RiskAssessmentResult(
        assessmentId=str(data["assessmentId"]),
        subjectRef=str(data["subjectRef"]),
        scenario=str(data["scenario"]),
        decision=str(data["decision"]),
        score=int(data["score"]),
        level=str(data["level"]),
        policyVersion=str(data["policyVersion"]),
        reasonCode=str(data["reasonCode"]),
        reasonExplanation=str(data.get("reasonExplanation", "")),
        assessedAt=str(data["assessedAt"]),
        evidenceRef=str(data.get("evidenceRef", f"evid-{data['assessmentId']}")),
        assessmentSnapshotHash=str(data.get("assessmentSnapshotHash", "")),
    )


def _block_to_json(block: RiskBlockApplied) -> dict[str, Any]:
    return block.to_event_payload()


def _block_from_json(data: Mapping[str, Any] | str) -> RiskBlockApplied:
    data = _json_obj(data)
    return RiskBlockApplied(
        blockId=str(data["blockId"]),
        subjectRef=str(data["subjectRef"]),
        scope=str(data["scope"]),
        reasonCode=str(data["reasonCode"]),
        policyVersion=str(data["policyVersion"]),
        evidenceRef=str(data["evidenceRef"]),
        blockedAt=str(data["blockedAt"]),
    )


def _coerce_dt(value: datetime | str) -> datetime:
    if isinstance(value, datetime):
        return value.astimezone(UTC) if value.tzinfo else value.replace(tzinfo=UTC)
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


def _json_array(data: Any) -> list[Any]:
    if isinstance(data, str):
        loaded = json.loads(data)
        return loaded if isinstance(loaded, list) else []
    return list(data) if isinstance(data, (list, tuple)) else []


def _evaluation_from_row(row: Any) -> RiskEvaluation:
    rules = tuple(
        RuleResult(
            rule_id=str(item["ruleId"]),
            result=RiskVerdict(str(item["result"])),
            detail=str(item["detail"]),
        )
        for item in _json_array(row[5])
    )
    signals = tuple(
        RiskSignal(
            signal_type=str(item["signalType"]),
            raw_value=item.get("rawValue"),
            normalized_score=int(item["normalizedScore"]),
        )
        for item in _json_array(row[6])
    )
    return RiskEvaluation(
        evaluation_id=str(row[0]),
        order_id=str(row[1]),
        account_id=str(row[2]),
        verdict=RiskVerdict(str(row[3])),
        score=int(row[4]),
        triggered_rules=rules,
        signals=signals,
        evaluated_at=_coerce_dt(row[7]),
        overridden_by=None if row[8] is None else str(row[8]),
        override_reason=None if row[9] is None else str(row[9]),
    )


def _evaluation_to_columns(evaluation: RiskEvaluation) -> tuple[Any, ...]:
    return (
        evaluation.evaluation_id,
        evaluation.order_id,
        evaluation.account_id,
        evaluation.verdict.value,
        evaluation.score,
        _jsonb_payload([rule.to_dict() for rule in evaluation.triggered_rules]),
        _jsonb_payload([
            {"signalType": signal.signal_type, "rawValue": signal.raw_value, "normalizedScore": signal.normalized_score}
            for signal in evaluation.signals
        ]),
        evaluation.evaluated_at,
        evaluation.overridden_by,
        evaluation.override_reason,
    )


class _TxState:
    def __init__(self, connection: Any) -> None:
        self.connection = connection


_TX: ContextVar[_TxState | None] = ContextVar("risk_compliance_postgres_tx", default=None)


class TransactionalOutboxPublisher:
    def __init__(self, repository: "PostgresAssessmentRepository", outbox: OutboxAppender | None = None) -> None:
        self._repository = repository
        self._outbox = outbox or OutboxAppender()

    def publish(self, envelope: EventEnvelope) -> None:
        def write(conn: Any) -> None:
            self._outbox.append(conn, envelope)
        self._repository.with_connection(write)


class PostgresAssessmentRepository:
    def __init__(self, pool: Any) -> None:
        self._pool = pool
        self._assessments = SnapshotRepository("risk_assessment_snapshots")
        self._blocks = SnapshotRepository("risk_block_snapshots")
        self._processed = ProcessedEventsGuard()

    @contextmanager
    def transaction(self):
        state = _TX.get()
        if state is not None:
            yield state.connection
            return
        with self._pool.connection() as conn:
            with conn.transaction():
                token = _TX.set(_TxState(conn))
                try:
                    yield conn
                finally:
                    _TX.reset(token)

    def with_connection(self, fn: Any) -> Any:
        state = _TX.get()
        if state is not None:
            return fn(state.connection)
        with self.transaction() as conn:
            return fn(conn)

    def get(self, assessment_id: str) -> RiskAssessmentResult:
        found = self.find(assessment_id)
        if found is None:
            raise AssessmentNotFoundError(assessment_id)
        return found

    def find(self, assessment_id: str) -> RiskAssessmentResult | None:
        def read(conn: Any) -> RiskAssessmentResult | None:
            snap = self._assessments.get(conn, assessment_id)
            return None if snap is None else _assessment_from_json(snap[1])
        return self.with_connection(read)

    def count(self) -> int:
        return self.with_connection(lambda conn: int(conn.execute("SELECT count(*) FROM risk_assessment_snapshots").fetchone()[0]))

    def values(self) -> tuple[RiskAssessmentResult, ...]:
        def read(conn: Any) -> tuple[RiskAssessmentResult, ...]:
            rows = conn.execute("SELECT data FROM risk_assessment_snapshots ORDER BY id").fetchall()
            return tuple(_assessment_from_json(row[0]) for row in rows)
        return self.with_connection(read)

    def save(self, assessment: RiskAssessmentResult) -> None:
        def write(conn: Any) -> None:
            snap = self._assessments.get(conn, assessment.assessmentId)
            self._assessments.save(conn, assessment.assessmentId, _assessment_to_json(assessment), None if snap is None else int(snap[0]))
        self.with_connection(write)

    def try_mark_processed(self, event_id: str) -> bool:
        return bool(self.with_connection(lambda conn: self._processed.try_mark_processed(conn, event_id, "events:journey-order")))

    def is_processed(self, event_id: str) -> bool:
        return self.with_connection(lambda conn: conn.execute("SELECT 1 FROM processed_events WHERE event_id = %s", (event_id,)).fetchone() is not None)

    def record_processed(self, event_id: str) -> None:
        self.with_connection(lambda conn: self._processed.try_mark_processed(conn, event_id, "events:journey-order"))

    def save_block(self, block: RiskBlockApplied) -> None:
        def write(conn: Any) -> None:
            snap = self._blocks.get(conn, block.subjectRef)
            self._blocks.save(conn, block.subjectRef, _block_to_json(block), None if snap is None else int(snap[0]))
        self.with_connection(write)

    def active_block(self, subject_ref: str) -> RiskBlockApplied:
        def read(conn: Any) -> RiskBlockApplied:
            snap = self._blocks.get(conn, subject_ref)
            if snap is None:
                raise BlockNotFoundError(subject_ref)
            return _block_from_json(snap[1])
        return self.with_connection(read)

    def remove_block(self, subject_ref: str) -> None:
        self.with_connection(lambda conn: conn.execute("DELETE FROM risk_block_snapshots WHERE id = %s", (subject_ref,)))

    def remember_order_account(self, order_id: str, account_id: str) -> None:
        self.with_connection(lambda conn: conn.execute("INSERT INTO risk_order_accounts(order_id, account_id) VALUES (%s, %s) ON CONFLICT (order_id) DO UPDATE SET account_id = EXCLUDED.account_id", (order_id, account_id)))

    def account_for_order(self, order_id: str) -> str | None:
        def read(conn: Any) -> str | None:
            row = conn.execute("SELECT account_id FROM risk_order_accounts WHERE order_id = %s", (order_id,)).fetchone()
            return None if row is None else str(row[0])
        return self.with_connection(read)

    def record_account_lift(self, account_id: str, lifted_at: datetime) -> None:
        def write(conn: Any) -> None:
            conn.execute("INSERT INTO risk_account_lifts(account_id, lifted_at) VALUES (%s, %s) ON CONFLICT (account_id) DO UPDATE SET lifted_at = EXCLUDED.lifted_at", (account_id, lifted_at))
            conn.execute("DELETE FROM risk_account_order_attempts WHERE account_id = %s AND occurred_at <= %s", (account_id, lifted_at))
        self.with_connection(write)

    def record_order_attempt(self, account_id: str, occurred_at: datetime) -> int:
        occurred_at = _coerce_dt(occurred_at)
        window_start = occurred_at - FREQUENCY_WINDOW
        def write(conn: Any) -> int:
            lifted = conn.execute("SELECT lifted_at FROM risk_account_lifts WHERE account_id = %s", (account_id,)).fetchone()
            lifted_at = lifted[0] if lifted else None
            conn.execute("INSERT INTO risk_account_order_attempts(account_id, occurred_at) VALUES (%s, %s) ON CONFLICT DO NOTHING", (account_id, occurred_at))
            if lifted_at is None:
                row = conn.execute("SELECT count(*) FROM risk_account_order_attempts WHERE account_id = %s AND occurred_at >= %s AND occurred_at <= %s", (account_id, window_start, occurred_at)).fetchone()
            else:
                row = conn.execute("SELECT count(*) FROM risk_account_order_attempts WHERE account_id = %s AND occurred_at >= %s AND occurred_at <= %s AND occurred_at > %s", (account_id, window_start, occurred_at, lifted_at)).fetchone()
            return int(row[0])
        return self.with_connection(write)

    def record_ip_order_attempt(self, source_ip: str, account_id: str, occurred_at: datetime) -> tuple[int, float, int]:
        occurred_at = _coerce_dt(occurred_at)
        window_start = occurred_at - FREQUENCY_WINDOW
        def write(conn: Any) -> tuple[int, float, int]:
            conn.execute(
                "INSERT INTO risk_ip_order_attempts(source_ip, account_id, occurred_at) VALUES (%s, %s, %s) ON CONFLICT DO NOTHING",
                (source_ip, account_id, occurred_at),
            )
            rows = conn.execute(
                "SELECT account_id, occurred_at FROM risk_ip_order_attempts WHERE source_ip = %s AND occurred_at >= %s AND occurred_at <= %s",
                (source_ip, window_start, occurred_at),
            ).fetchall()
            half_life_seconds = IP_FREQUENCY_DECAY_HALF_LIFE.total_seconds()
            weighted_attempts = sum(
                0.5 ** ((occurred_at - _coerce_dt(row[1])).total_seconds() / half_life_seconds)
                for row in rows
            )
            return len(rows), weighted_attempts, len({str(row[0]) for row in rows})
        return self.with_connection(write)


class PostgresRiskEvaluationRepository:
    def __init__(self, pool: Any) -> None:
        self._pool = pool

    @contextmanager
    def transaction(self):
        state = _TX.get()
        if state is not None:
            yield state.connection
            return
        with self._pool.connection() as conn:
            with conn.transaction():
                token = _TX.set(_TxState(conn))
                try:
                    yield conn
                finally:
                    _TX.reset(token)

    def with_connection(self, fn: Any) -> Any:
        state = _TX.get()
        if state is not None:
            return fn(state.connection)
        with self.transaction() as conn:
            return fn(conn)

    def save(self, evaluation: RiskEvaluation) -> None:
        def write(conn: Any) -> None:
            conn.execute(
                """
                INSERT INTO risk_evaluation_snapshots(
                  evaluation_id, order_id, account_id, verdict, score, triggered_rules,
                  signals, evaluated_at, overridden_by, override_reason
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (evaluation_id) DO UPDATE SET
                  order_id = EXCLUDED.order_id,
                  account_id = EXCLUDED.account_id,
                  verdict = EXCLUDED.verdict,
                  score = EXCLUDED.score,
                  triggered_rules = EXCLUDED.triggered_rules,
                  signals = EXCLUDED.signals,
                  evaluated_at = EXCLUDED.evaluated_at,
                  overridden_by = EXCLUDED.overridden_by,
                  override_reason = EXCLUDED.override_reason,
                  updated_at = now()
                """,
                _evaluation_to_columns(evaluation),
            )
        self.with_connection(write)

    def get(self, evaluation_id: str) -> RiskEvaluation:
        found = self.find(evaluation_id)
        if found is None:
            from risk_compliance.evaluation import EvaluationNotFoundError

            raise EvaluationNotFoundError(evaluation_id)
        return found

    def find(self, evaluation_id: str) -> RiskEvaluation | None:
        def read(conn: Any) -> RiskEvaluation | None:
            row = conn.execute(
                """
                SELECT evaluation_id, order_id, account_id, verdict, score, triggered_rules,
                       signals, evaluated_at, overridden_by, override_reason
                FROM risk_evaluation_snapshots
                WHERE evaluation_id = %s
                """,
                (evaluation_id,),
            ).fetchone()
            return None if row is None else _evaluation_from_row(row)
        return self.with_connection(read)

    def account_created_at(self, account_id: str) -> datetime | None:
        def read(conn: Any) -> datetime | None:
            row = conn.execute("SELECT registered_at FROM risk_account_profiles WHERE account_id = %s", (account_id,)).fetchone()
            return None if row is None else _coerce_dt(row[0])
        return self.with_connection(read)

    def record_account_registered(self, account_id: str, occurred_at: datetime) -> None:
        self.with_connection(
            lambda conn: conn.execute(
                "INSERT INTO risk_account_profiles(account_id, registered_at) VALUES (%s, %s) ON CONFLICT (account_id) DO NOTHING",
                (account_id, _coerce_dt(occurred_at)),
            )
        )

    def record_order(self, record: Any) -> None:
        self.with_connection(
            lambda conn: conn.execute(
                """
                INSERT INTO risk_route_booking_history(order_id, account_id, origin, destination, traveler_refs, departure_date, occurred_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (order_id) DO NOTHING
                """,
                (
                    record.order_id,
                    record.account_id,
                    record.origin,
                    record.destination,
                    _jsonb_payload(list(record.traveler_refs)),
                    record.departure_date,
                    _coerce_dt(record.occurred_at),
                ),
            )
        )

    def orders_for_account_since(self, account_id: str, since: datetime) -> tuple[Any, ...]:
        def read(conn: Any) -> tuple[Any, ...]:
            from risk_compliance.evaluation import OrderHistoryRecord

            rows = conn.execute(
                """
                SELECT account_id, order_id, origin, destination, traveler_refs, departure_date, occurred_at
                FROM risk_route_booking_history
                WHERE account_id = %s AND occurred_at >= %s
                ORDER BY occurred_at
                """,
                (account_id, _coerce_dt(since)),
            ).fetchall()
            return tuple(
                OrderHistoryRecord(
                    account_id=str(row[0]),
                    order_id=str(row[1]),
                    origin=str(row[2]),
                    destination=str(row[3]),
                    traveler_refs=tuple(str(value) for value in _json_array(row[4])),
                    departure_date=str(row[5]),
                    occurred_at=_coerce_dt(row[6]),
                )
                for row in rows
            )
        return self.with_connection(read)

    def record_payment_attempt(self, account_id: str, occurred_at: datetime) -> None:
        self.with_connection(
            lambda conn: conn.execute(
                "INSERT INTO risk_payment_attempt_history(account_id, occurred_at) VALUES (%s, %s) ON CONFLICT DO NOTHING",
                (account_id, _coerce_dt(occurred_at)),
            )
        )

    def payment_attempts_since(self, account_id: str, since: datetime) -> int:
        def read(conn: Any) -> int:
            row = conn.execute(
                "SELECT count(*) FROM risk_payment_attempt_history WHERE account_id = %s AND occurred_at >= %s",
                (account_id, _coerce_dt(since)),
            ).fetchone()
            return int(row[0])
        return self.with_connection(read)

    def record_refund(self, account_id: str, occurred_at: datetime) -> None:
        self.with_connection(
            lambda conn: conn.execute(
                "INSERT INTO risk_refund_history(account_id, occurred_at) VALUES (%s, %s) ON CONFLICT DO NOTHING",
                (account_id, _coerce_dt(occurred_at)),
            )
        )

    def refunds_since(self, account_id: str, since: datetime) -> tuple[datetime, ...]:
        def read(conn: Any) -> tuple[datetime, ...]:
            rows = conn.execute(
                "SELECT occurred_at FROM risk_refund_history WHERE account_id = %s AND occurred_at >= %s ORDER BY occurred_at",
                (account_id, _coerce_dt(since)),
            ).fetchall()
            return tuple(_coerce_dt(row[0]) for row in rows)
        return self.with_connection(read)
