from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from datetime import UTC, datetime
import json
from typing import Any

from train_ticket_platform.storage import OutboxAppender, ProcessedEventsGuard, SnapshotRepository

from transfer_management.application.service import InMemoryStore, NotFoundError
from transfer_management.domain import (
    ActorRef,
    Connection,
    ConnectionContract,
    ConnectionStatus,
    ConnectionWindow,
    ContractStatus,
    ContractType,
    MctRule,
    MctRuleStatus,
    NodeType,
    RecoveryCaseMapping,
    RecoveryTriggerStatus,
    ReportType,
    RiskEvaluation,
    RiskLevel,
    SegmentStatusReport,
    TransferCategory,
    TransferPlan,
    TransferPlanStatus,
    parse_dt,
)


def _dt(value: datetime) -> str:
    return (value if value.tzinfo else value.replace(tzinfo=UTC)).astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _parse(value: Any) -> datetime | None:
    if not value:
        return None
    return parse_dt(value)


def _json_obj(data: Mapping[str, Any] | str) -> Mapping[str, Any]:
    return json.loads(data) if isinstance(data, str) else data


def plan_to_json(plan: TransferPlan) -> dict[str, Any]:
    return {"transferPlanId": plan.transferPlanId, "itineraryRef": plan.itineraryRef, "planningSnapshotVersion": plan.planningSnapshotVersion, "journeyOrderId": plan.journeyOrderId, "travelerRefs": list(plan.travelerRefs), "status": plan.status.value, "connections": list(plan.connections), "evaluationVersion": plan.evaluationVersion, "createdAt": _dt(plan.createdAt), "updatedAt": _dt(plan.updatedAt), "expiresAt": _dt(plan.expiresAt) if plan.expiresAt else None}


def plan_from_json(data: Mapping[str, Any] | str, version: int = 0) -> TransferPlan:
    data = _json_obj(data)
    return TransferPlan(str(data["transferPlanId"]), str(data["itineraryRef"]), int(data["planningSnapshotVersion"]), data.get("journeyOrderId"), tuple(data.get("travelerRefs") or ()), TransferPlanStatus(str(data["status"])), tuple(data.get("connections") or ()), int(data["evaluationVersion"]), _parse(data.get("createdAt")) or datetime.now(UTC), _parse(data.get("updatedAt")) or datetime.now(UTC), _parse(data.get("expiresAt")), version=version)


def evaluation_to_json(e: RiskEvaluation) -> dict[str, Any]:
    return e.to_json()


def evaluation_from_json(data: Mapping[str, Any]) -> RiskEvaluation:
    return RiskEvaluation(str(data["riskEvaluationId"]), RiskLevel(str(data["riskLevel"])), str(data["mctRuleId"]), int(data["mctRuleVersion"]), int(data["availableMinutes"]), int(data["requiredMinutes"]), tuple(data.get("reasons") or ()), _parse(data.get("evaluatedAt")) or datetime.now(UTC), str(data.get("riskPolicyVersion") or "builtin-v1"))


def window_from_json(data: Mapping[str, Any]) -> ConnectionWindow:
    return ConnectionWindow(_parse(data.get("plannedArrivalAt")) or datetime.now(UTC), _parse(data.get("nextDepartureAt")) or datetime.now(UTC), _parse(data.get("nextCutoffAt")) or datetime.now(UTC), int(data["availableMinutes"]), int(data["mctMinutes"]), int(data["bufferMinutes"]), _parse(data.get("actualArrivalAt")))


def recovery_from_json(data: Mapping[str, Any] | None) -> RecoveryCaseMapping | None:
    if not data:
        return None
    return RecoveryCaseMapping(RecoveryTriggerStatus(str(data["recoveryTriggerStatus"])), str(data["outboundIdempotencyKey"]), tuple(data.get("caseIds") or ()), data.get("disruptionId"), data.get("incidentId"), _parse(data.get("openedAt")), data.get("failureReason"))


def connection_to_json(c: Connection) -> dict[str, Any]:
    return {"connectionId": c.connectionId, "transferPlanId": c.transferPlanId, "itineraryRef": c.itineraryRef, "previousSegmentRef": c.previousSegmentRef, "nextSegmentRef": c.nextSegmentRef, "travelerRefs": list(c.travelerRefs), "fromNodeRef": c.fromNodeRef, "toNodeRef": c.toNodeRef, "fromNodeType": c.fromNodeType.value, "toNodeType": c.toNodeType.value, "transferCategory": c.transferCategory.value, "contractId": c.contractId, "contractType": c.contractType.value, "status": c.status.value, "latestEvaluation": evaluation_to_json(c.latestEvaluation), "window": c.window.to_json(), "createdAt": _dt(c.createdAt), "updatedAt": _dt(c.updatedAt), "journeyOrderId": c.journeyOrderId, "recovery": c.recovery.to_json() if c.recovery else None, "serviceDate": c.serviceDate, "scheduledServiceRef": c.scheduledServiceRef}


def connection_from_json(data: Mapping[str, Any] | str, version: int = 0) -> Connection:
    data = _json_obj(data)
    return Connection(str(data["connectionId"]), str(data["transferPlanId"]), str(data["itineraryRef"]), str(data["previousSegmentRef"]), str(data["nextSegmentRef"]), tuple(data.get("travelerRefs") or ()), str(data["fromNodeRef"]), str(data["toNodeRef"]), NodeType(str(data["fromNodeType"])), NodeType(str(data["toNodeType"])), TransferCategory(str(data["transferCategory"])), str(data["contractId"]), ContractType(str(data["contractType"])), ConnectionStatus(str(data["status"])), evaluation_from_json(data["latestEvaluation"]), window_from_json(data["window"]), _parse(data.get("createdAt")) or datetime.now(UTC), _parse(data.get("updatedAt")) or datetime.now(UTC), data.get("journeyOrderId"), recovery_from_json(data.get("recovery")), data.get("serviceDate"), data.get("scheduledServiceRef"), version)


def contract_to_json(c: ConnectionContract) -> dict[str, Any]:
    return {"connectionContractId": c.connectionContractId, "connectionId": c.connectionId, "contractType": c.contractType.value, "status": c.status.value, "responsibleParty": c.responsibleParty, "coverageSummary": c.coverageSummary, "disclosureVersion": c.disclosureVersion, "createdAt": _dt(c.createdAt), "updatedAt": _dt(c.updatedAt), "termsSnapshotRef": c.termsSnapshotRef, "confirmedAt": _dt(c.confirmedAt) if c.confirmedAt else None}


def contract_from_json(data: Mapping[str, Any] | str, version: int = 0) -> ConnectionContract:
    data = _json_obj(data)
    return ConnectionContract(str(data["connectionContractId"]), str(data["connectionId"]), ContractType(str(data["contractType"])), ContractStatus(str(data["status"])), str(data["responsibleParty"]), str(data["coverageSummary"]), str(data["disclosureVersion"]), _parse(data.get("createdAt")) or datetime.now(UTC), _parse(data.get("updatedAt")) or datetime.now(UTC), data.get("termsSnapshotRef"), _parse(data.get("confirmedAt")), version)


def rule_from_json(data: Mapping[str, Any] | str) -> MctRule:
    data = _json_obj(data)
    return MctRule(str(data["mctRuleId"]), int(data["version"]), MctRuleStatus(str(data["status"])), NodeType(str(data["fromNodeType"])), NodeType(str(data["toNodeType"])), TransferCategory(str(data["transferCategory"])), int(data["minimumMinutes"]), dict(data.get("conditions") or {}), _parse(data.get("validFrom")) or datetime.now(UTC), _parse(data.get("validUntil")), _parse(data.get("publishedAt")), _parse(data.get("retiredAt")))


def report_to_json(r: SegmentStatusReport) -> dict[str, Any]:
    return r.to_json()


@dataclass
class _UnitOfWorkState:
    connection: Any | None = None
    loaded_versions: dict[tuple[str, str], int] | None = None

    def __post_init__(self) -> None:
        if self.loaded_versions is None:
            self.loaded_versions = {}


_UNIT_OF_WORK: ContextVar[_UnitOfWorkState | None] = ContextVar("transfer_management_uow", default=None)


class PostgresTransferManagementStore(InMemoryStore):
    def __init__(self, pool: Any, outbox: OutboxAppender | None = None) -> None:
        self._pool = pool
        self._outbox = outbox or OutboxAppender()
        self._plans = SnapshotRepository("transfer_plan_snapshots")
        self._connections = SnapshotRepository("connection_snapshots")
        self._contracts = SnapshotRepository("connection_contract_snapshots")
        self._processed = ProcessedEventsGuard()

    @contextmanager
    def transaction(self):
        state = _UNIT_OF_WORK.get()
        if state is not None and state.connection is not None:
            yield state.connection; return
        with self._pool.connection() as conn:
            with conn.transaction():
                token = _UNIT_OF_WORK.set(_UnitOfWorkState(connection=conn))
                try:
                    yield conn
                finally:
                    _UNIT_OF_WORK.reset(token)

    @contextmanager
    def unit_of_work(self):
        if _UNIT_OF_WORK.get() is not None:
            yield; return
        token = _UNIT_OF_WORK.set(_UnitOfWorkState())
        try:
            yield
        finally:
            _UNIT_OF_WORK.reset(token)

    def _with_conn(self, func: Callable[[Any], Any]) -> Any:
        state = _UNIT_OF_WORK.get()
        if state is not None and state.connection is not None:
            return func(state.connection)
        with self._pool.connection() as conn:
            return func(conn)

    def _remember(self, aggregate: str, aggregate_id: str, version: int) -> None:
        state = _UNIT_OF_WORK.get()
        if state and state.loaded_versions is not None:
            state.loaded_versions[(aggregate, aggregate_id)] = version

    def _take(self, aggregate: str, aggregate_id: str) -> int | None:
        state = _UNIT_OF_WORK.get()
        return None if state is None or state.loaded_versions is None else state.loaded_versions.pop((aggregate, aggregate_id), None)

    def save_plan(self, plan: TransferPlan) -> None:
        def write(conn: Any) -> None:
            self._plans.save(conn, plan.transferPlanId, plan_to_json(plan), self._take("plan", plan.transferPlanId))
        self._with_conn(write)

    def get_plan(self, plan_id: str) -> TransferPlan:
        def read(conn: Any) -> TransferPlan:
            snap = self._plans.get(conn, plan_id)
            if snap is None: raise NotFoundError(f"transfer plan not found: {plan_id}")
            version, data = snap; self._remember("plan", plan_id, version); return plan_from_json(data, version)
        return self._with_conn(read)

    def save_connection(self, connection: Connection) -> None:
        def write(conn: Any) -> None:
            self._connections.save(conn, connection.connectionId, connection_to_json(connection), self._take("connection", connection.connectionId))
        self._with_conn(write)

    def get_connection(self, connection_id: str) -> Connection:
        def read(conn: Any) -> Connection:
            snap = self._connections.get(conn, connection_id)
            if snap is None: raise NotFoundError(f"connection not found: {connection_id}")
            version, data = snap; self._remember("connection", connection_id, version); return connection_from_json(data, version)
        return self._with_conn(read)

    def _list_connections(self, where: str, params: tuple[Any, ...]) -> tuple[Connection, ...]:
        def read(conn: Any) -> tuple[Connection, ...]:
            rows = conn.execute(f"SELECT id, version, data FROM connection_snapshots WHERE {where} ORDER BY data->>'createdAt'", params).fetchall()
            items = []
            for aggregate_id, version, data in rows:
                self._remember("connection", str(aggregate_id), int(version)); items.append(connection_from_json(data, int(version)))
            return tuple(items)
        return self._with_conn(read)

    def list_connections_for_plan(self, plan_id: str) -> tuple[Connection, ...]:
        return self._list_connections("data->>'transferPlanId' = %s", (plan_id,))

    def list_connections_for_journey(self, journey_order_id: str) -> tuple[Connection, ...]:
        return self._list_connections("data->>'journeyOrderId' = %s", (journey_order_id,))

    def list_connections_for_segment(self, segment_ref: str) -> tuple[Connection, ...]:
        return self._list_connections("data->>'previousSegmentRef' = %s OR data->>'nextSegmentRef' = %s", (segment_ref, segment_ref))

    def find_connection_by_case_id(self, case_id: str) -> Connection | None:
        rows = self._list_connections("data->'recovery'->'caseIds' ? %s", (case_id,))
        return rows[0] if rows else None

    def save_contract(self, contract: ConnectionContract) -> None:
        def write(conn: Any) -> None:
            self._contracts.save(conn, contract.connectionContractId, contract_to_json(contract), self._take("contract", contract.connectionContractId))
        self._with_conn(write)

    def get_contract(self, contract_id: str) -> ConnectionContract:
        def read(conn: Any) -> ConnectionContract:
            snap = self._contracts.get(conn, contract_id)
            if snap is None: raise NotFoundError(f"connection contract not found: {contract_id}")
            version, data = snap; self._remember("contract", contract_id, version); return contract_from_json(data, version)
        return self._with_conn(read)

    def save_mct_rule(self, rule: MctRule) -> None:
        def write(conn: Any) -> None:
            conn.execute("INSERT INTO mct_rule_snapshots(id, version, data) VALUES (%s, %s, %s) ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version, data = EXCLUDED.data, updated_at = now()", (rule.mctRuleId, rule.version, self._json(rule.to_json())))
        self._with_conn(write)

    def get_mct_rule(self, rule_id: str) -> MctRule:
        def read(conn: Any) -> MctRule:
            row = conn.execute("SELECT data FROM mct_rule_snapshots WHERE id = %s", (rule_id,)).fetchone()
            if not row: raise NotFoundError(f"MCT rule not found: {rule_id}")
            return rule_from_json(row[0])
        return self._with_conn(read)

    def list_mct_rules(self, **filters: Any) -> tuple[MctRule, ...]:
        def read(conn: Any) -> tuple[MctRule, ...]:
            rows = conn.execute("SELECT data FROM mct_rule_snapshots ORDER BY data->>'mctRuleId'").fetchall()
            return tuple(rule_from_json(row[0]) for row in rows)
        return self._with_conn(read)

    def save_report(self, report: SegmentStatusReport) -> None:
        def write(conn: Any) -> None:
            conn.execute("INSERT INTO segment_status_reports(id, segment_ref, source_key, data) VALUES (%s, %s, %s, %s) ON CONFLICT (source_key) DO NOTHING", (report.segmentStatusReportId, report.segmentRef, f"{report.sourceSystem}\u001f{report.sourceRecordId}\u001f{report.segmentRef}\u001f{report.reportType.value}\u001f{report.observedAt.isoformat()}", self._json(report_to_json(report))))
        self._with_conn(write)

    def append_outbox(self, envelopes: Iterable[Any]) -> None:
        def write(conn: Any) -> None:
            for envelope in envelopes:
                self._outbox.append(conn, envelope)
        self._with_conn(write)

    def mark_processed(self, event_id: str, stream: str | None = None) -> bool:
        return bool(self._with_conn(lambda conn: self._processed.try_mark_processed(conn, event_id, stream)))

    @staticmethod
    def _json(data: Mapping[str, Any]) -> Any:
        try:
            from psycopg.types.json import Jsonb
            return Jsonb(dict(data))
        except ImportError:
            return json.dumps(dict(data), separators=(",", ":"))
