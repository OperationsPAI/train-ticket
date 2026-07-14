from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from datetime import UTC, datetime
import json
from typing import Any

from train_ticket_platform.storage import OutboxAppender, ProcessedEventsGuard, SnapshotRepository

from disruption_recovery.application.service import InMemoryStore, NotFoundError, ServiceAlert
from disruption_recovery.domain import Incident, RecoveryCase, RecoveryCaseStatus, RecoveryExecution, RecoveryOption, RecoveryOptionSet, RecoveryOptionType, ExecutionTarget


def _dt(value: datetime) -> str:
    return (value if value.tzinfo else value.replace(tzinfo=UTC)).astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _parse_dt(value: str | None) -> datetime | None:
    if not value:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


def _json_obj(data: Mapping[str, Any] | str) -> Mapping[str, Any]:
    return json.loads(data) if isinstance(data, str) else data


def _option_to_json(option: RecoveryOption) -> dict[str, Any]:
    return option.to_json()


def _option_from_json(data: Mapping[str, Any]) -> RecoveryOption:
    return RecoveryOption(str(data["optionId"]), RecoveryOptionType(str(data["optionType"])), str(data["title"]), str(data["description"]), ExecutionTarget(str(data["executionTarget"])), data.get("refund"), data.get("compensation"), data.get("manualReason"), data.get("reaccommodation"), _parse_dt(data.get("expiresAt")))


def _option_set_to_json(option_set: RecoveryOptionSet | None) -> dict[str, Any] | None:
    return option_set.to_json() if option_set else None


def _option_set_from_json(data: Mapping[str, Any] | None) -> RecoveryOptionSet | None:
    if not data:
        return None
    return RecoveryOptionSet(str(data["optionSetId"]), str(data["caseId"]), tuple(_option_from_json(item) for item in data.get("options", ())), _parse_dt(str(data["generatedAt"])) or datetime.now(UTC), _parse_dt(data.get("expiresAt")), bool(data["requiresUserChoice"]))


def _execution_to_json(execution: RecoveryExecution | None) -> dict[str, Any] | None:
    return execution.to_json() if execution else None


def _execution_from_json(data: Mapping[str, Any] | None) -> RecoveryExecution | None:
    if not data:
        return None
    return RecoveryExecution(str(data["executionId"]), ExecutionTarget(str(data["target"])), data.get("idempotencyKey"), data.get("externalRef"), _parse_dt(str(data["startedAt"])) or datetime.now(UTC), _parse_dt(data.get("completedAt")), data.get("failureReason"))


def incident_to_json(incident: Incident) -> dict[str, Any]:
    return {"incidentId": incident.incidentId, "status": incident.status, "disruptionType": incident.disruptionType, "scheduledServiceRef": incident.scheduledServiceRef, "segmentRefs": list(incident.segmentRefs), "serviceDate": incident.serviceDate, "evidenceRefs": list(incident.evidenceRefs), "affectedOrderIds": list(incident.affectedOrderIds), "openedAt": _dt(incident.openedAt), "updatedAt": _dt(incident.updatedAt)}


def incident_from_json(data: Mapping[str, Any] | str, version: int = 0) -> Incident:
    data = _json_obj(data)
    return Incident(str(data["incidentId"]), str(data["status"]), str(data["disruptionType"]), data.get("scheduledServiceRef"), tuple(data.get("segmentRefs") or ()), str(data["serviceDate"]), tuple(data.get("evidenceRefs") or ()), tuple(data.get("affectedOrderIds") or ()), _parse_dt(str(data["openedAt"])) or datetime.now(UTC), _parse_dt(str(data["updatedAt"])) or datetime.now(UTC), version)


def case_to_json(case: RecoveryCase) -> dict[str, Any]:
    return {"caseId": case.caseId, "incidentId": case.incidentId, "journeyOrderId": case.journeyOrderId, "affectedScope": dict(case.affectedScope), "status": case.status.value, "openedAt": _dt(case.openedAt), "updatedAt": _dt(case.updatedAt), "optionSet": _option_set_to_json(case.optionSet), "selectedOptionId": case.selectedOptionId, "execution": _execution_to_json(case.execution)}


def case_from_json(data: Mapping[str, Any] | str, version: int = 0) -> RecoveryCase:
    data = _json_obj(data)
    return RecoveryCase(str(data["caseId"]), str(data["incidentId"]), str(data["journeyOrderId"]), dict(data["affectedScope"]), RecoveryCaseStatus(str(data["status"])), _parse_dt(str(data["openedAt"])) or datetime.now(UTC), _parse_dt(str(data["updatedAt"])) or datetime.now(UTC), _option_set_from_json(data.get("optionSet")), data.get("selectedOptionId"), _execution_from_json(data.get("execution")), version)


def alert_to_json(alert: ServiceAlert) -> dict[str, Any]:
    return alert.to_json()


def alert_from_json(data: Mapping[str, Any] | str, version: int = 0) -> ServiceAlert:
    del version
    return ServiceAlert.from_payload(_json_obj(data))


@dataclass
class _UnitOfWorkState:
    connection: Any | None = None
    loaded_versions: dict[tuple[str, str], int] | None = None

    def __post_init__(self) -> None:
        if self.loaded_versions is None:
            self.loaded_versions = {}


_UNIT_OF_WORK: ContextVar[_UnitOfWorkState | None] = ContextVar("disruption_recovery_uow", default=None)


class PostgresDisruptionRecoveryStore(InMemoryStore):
    def __init__(self, pool: Any, outbox: OutboxAppender | None = None) -> None:
        self._pool = pool
        self._outbox = outbox or OutboxAppender()
        self._incidents = SnapshotRepository("incident_snapshots")
        self._cases = SnapshotRepository("recovery_case_snapshots")
        self._alerts = SnapshotRepository("service_alert_snapshots")
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
        if state is None or state.loaded_versions is None:
            return None
        return state.loaded_versions.pop((aggregate, aggregate_id), None)

    def get_incident(self, incident_id: str) -> Incident:
        def read(conn: Any) -> Incident:
            snap = self._incidents.get(conn, incident_id)
            if snap is None:
                raise NotFoundError(f"incident not found: {incident_id}")
            version, data = snap; self._remember("incident", incident_id, version); return incident_from_json(data, version)
        return self._with_conn(read)

    def save_incident(self, incident: Incident) -> None:
        def write(conn: Any) -> None:
            expected = self._take("incident", incident.incidentId)
            self._incidents.save(conn, incident.incidentId, incident_to_json(incident), expected)
        self._with_conn(write)

    def find_incident_by_merge_key(self, scheduled_service_ref: str, service_date: str) -> Incident | None:
        def read(conn: Any) -> Incident | None:
            row = conn.execute("SELECT id, version, data FROM incident_snapshots WHERE data->>'scheduledServiceRef' = %s AND data->>'serviceDate' = %s ORDER BY id LIMIT 1", (scheduled_service_ref, service_date)).fetchone()
            if not row: return None
            self._remember("incident", str(row[0]), int(row[1])); return incident_from_json(row[2], int(row[1]))
        return self._with_conn(read)

    def get_case(self, case_id: str) -> RecoveryCase:
        def read(conn: Any) -> RecoveryCase:
            snap = self._cases.get(conn, case_id)
            if snap is None: raise NotFoundError(f"recovery case not found: {case_id}")
            version, data = snap; self._remember("case", case_id, version); return case_from_json(data, version)
        return self._with_conn(read)

    def save_case(self, case: RecoveryCase) -> None:
        def write(conn: Any) -> None:
            expected = self._take("case", case.caseId)
            self._cases.save(conn, case.caseId, case_to_json(case), expected)
        self._with_conn(write)

    def find_case(self, incident_id: str, order_id: str) -> RecoveryCase | None:
        def read(conn: Any) -> RecoveryCase | None:
            row = conn.execute("SELECT id, version, data FROM recovery_case_snapshots WHERE data->>'incidentId' = %s AND data->>'journeyOrderId' = %s ORDER BY id LIMIT 1", (incident_id, order_id)).fetchone()
            if not row: return None
            self._remember("case", str(row[0]), int(row[1])); return case_from_json(row[2], int(row[1]))
        return self._with_conn(read)

    def list_cases(self, incident_id: str, status: str | None, limit: int, offset: int) -> tuple[tuple[RecoveryCase, ...], int]:
        def read(conn: Any) -> tuple[tuple[RecoveryCase, ...], int]:
            params: list[Any] = [incident_id]
            where = "data->>'incidentId' = %s"
            if status:
                where += " AND data->>'status' = %s"; params.append(status)
            total = int(conn.execute(f"SELECT count(*) FROM recovery_case_snapshots WHERE {where}", tuple(params)).fetchone()[0])
            rows = conn.execute(f"SELECT id, version, data FROM recovery_case_snapshots WHERE {where} ORDER BY data->>'openedAt' LIMIT %s OFFSET %s", tuple(params + [limit, offset])).fetchall()
            items = []
            for aggregate_id, version, data in rows:
                self._remember("case", str(aggregate_id), int(version)); items.append(case_from_json(data, int(version)))
            return tuple(items), total
        return self._with_conn(read)

    def find_case_by_post_sales_case(self, post_sales_case_id: str) -> RecoveryCase | None:
        def read(conn: Any) -> RecoveryCase | None:
            row = conn.execute("SELECT id, version, data FROM recovery_case_snapshots WHERE data->'execution'->>'externalRef' = %s ORDER BY id LIMIT 1", (post_sales_case_id,)).fetchone()
            if not row: return None
            self._remember("case", str(row[0]), int(row[1])); return case_from_json(row[2], int(row[1]))
        return self._with_conn(read)

    def index_journey_order(self, order_id: str, segment_refs: tuple[str, ...]) -> None:
        clean_order_id = str(order_id).strip()
        if not clean_order_id:
            return
        unique_segment_refs = tuple(dict.fromkeys(str(item).strip() for item in segment_refs if str(item).strip()))
        if not unique_segment_refs:
            return
        def write(conn: Any) -> None:
            for segment_ref in unique_segment_refs:
                conn.execute("INSERT INTO segment_order_index(segment_ref, journey_order_id) VALUES (%s, %s) ON CONFLICT DO NOTHING", (segment_ref, clean_order_id))
        self._with_conn(write)

    def find_orders_by_segment(self, segment_ref: str) -> tuple[str, ...]:
        def read(conn: Any) -> tuple[str, ...]:
            rows = conn.execute("SELECT journey_order_id FROM segment_order_index WHERE segment_ref = %s ORDER BY journey_order_id", (segment_ref,)).fetchall()
            return tuple(str(row[0]) for row in rows)
        return self._with_conn(read)

    def save_service_alert(self, alert: ServiceAlert) -> None:
        def write(conn: Any) -> None:
            expected = self._take("service_alert", alert.serviceAlertId)
            if expected is None and self._alerts.get(conn, alert.serviceAlertId) is not None:
                return
            self._alerts.save(conn, alert.serviceAlertId, alert_to_json(alert), expected)
        self._with_conn(write)

    def get_service_alert(self, service_alert_id: str) -> ServiceAlert:
        def read(conn: Any) -> ServiceAlert:
            snap = self._alerts.get(conn, service_alert_id)
            if snap is None:
                raise NotFoundError(f"service alert not found: {service_alert_id}")
            version, data = snap; self._remember("service_alert", service_alert_id, version); return alert_from_json(data, version)
        return self._with_conn(read)

    def list_service_alerts(self, incident_id: str | None, journey_order_id: str | None, limit: int, offset: int) -> tuple[tuple[ServiceAlert, ...], int]:
        def read(conn: Any) -> tuple[tuple[ServiceAlert, ...], int]:
            params: list[Any] = []
            clauses: list[str] = []
            if incident_id:
                clauses.append("data->>'incidentId' = %s"); params.append(incident_id)
            if journey_order_id:
                clauses.append("data->'affectedOrderIds' ? %s"); params.append(journey_order_id)
            where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
            total = int(conn.execute(f"SELECT count(*) FROM service_alert_snapshots{where}", tuple(params)).fetchone()[0])
            rows = conn.execute(f"SELECT id, version, data FROM service_alert_snapshots{where} ORDER BY data->>'publishedAt' DESC LIMIT %s OFFSET %s", tuple(params + [limit, offset])).fetchall()
            items = []
            for aggregate_id, version, data in rows:
                self._remember("service_alert", str(aggregate_id), int(version)); items.append(alert_from_json(data, int(version)))
            return tuple(items), total
        return self._with_conn(read)

    def append_outbox(self, envelopes: Iterable[Any]) -> None:
        def write(conn: Any) -> None:
            for envelope in envelopes: self._outbox.append(conn, envelope)
        self._with_conn(write)

    def mark_processed(self, event_id: str, stream: str | None = None) -> bool:
        def write(conn: Any) -> bool:
            return self._processed.try_mark_processed(conn, event_id, stream)
        return bool(self._with_conn(write))
