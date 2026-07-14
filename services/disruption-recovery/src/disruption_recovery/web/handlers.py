from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Request, Query
from pydantic import BaseModel, ConfigDict, Field

from disruption_recovery.application.service import DisruptionRecoveryService
from disruption_recovery.domain import RecoveryCaseStatus

router = APIRouter(prefix="/api/v1")


class LooseModel(BaseModel):
    model_config = ConfigDict(extra="allow")


class ReportDisruptionRequest(LooseModel):
    disruptionType: str
    serviceDate: str
    evidence: dict[str, Any]
    affectedOrderIds: list[str] = Field(default_factory=list)
    reportedBy: dict[str, Any]
    scheduledServiceRef: str | None = None
    segmentRef: str | None = None
    accountId: str | None = None
    refundScope: dict[str, Any] | None = None
    autoRecovery: str | None = None


class SelectOptionRequest(LooseModel):
    optionId: str
    selectedBy: dict[str, Any]
    selectionReason: str | None = None


class ManualResolveRequest(LooseModel):
    outcome: str
    resolvedBy: dict[str, Any] | None = None
    reason: str | None = None
    manualRef: str | None = None


class CloseCaseRequest(LooseModel):
    closedBy: dict[str, Any]
    closeReason: str


def _service(request: Request) -> DisruptionRecoveryService:
    return request.app.state.disruption_recovery_service


def _corr(request: Request) -> str:
    return str(getattr(request.state, "correlation_id", None) or request.headers.get("X-Correlation-Id") or request.headers.get("X-Correlation-ID") or "corr-disruption-recovery")


def _cause(request: Request) -> str:
    idem = getattr(request.state, "idempotency_decision", None)
    return str(getattr(idem, "key", None) or request.headers.get("Idempotency-Key") or getattr(request.state, "request_id", "cmd-disruption-recovery"))


@router.post("/disruptions", status_code=202)
def report_disruption(body: ReportDisruptionRequest, request: Request) -> dict[str, Any]:
    return _service(request).report_disruption(body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.get("/incidents/{incident_id}")
def get_incident(incident_id: str, request: Request) -> dict[str, Any]:
    return _service(request).store.get_incident(incident_id).to_json()


@router.get("/recovery-cases/{case_id}")
def get_case(case_id: str, request: Request) -> dict[str, Any]:
    return _service(request).store.get_case(case_id).to_json()


@router.get("/recovery-cases")
def list_cases(request: Request, incidentId: str = Query(...), status: str | None = None, limit: int = 20, offset: int = 0) -> dict[str, Any]:
    safe_limit = max(1, min(limit, 100))
    safe_offset = max(0, offset)
    items, total = _service(request).store.list_cases(incidentId, status, safe_limit, safe_offset)
    return {"items": [item.to_json() for item in items], "total": total, "limit": safe_limit, "offset": safe_offset}


@router.get("/service-alerts/{service_alert_id}")
def get_service_alert(service_alert_id: str, request: Request) -> dict[str, Any]:
    return _service(request).store.get_service_alert(service_alert_id).to_json()


@router.get("/service-alerts")
def list_service_alerts(request: Request, incidentId: str | None = None, journeyOrderId: str | None = None, limit: int = 20, offset: int = 0) -> dict[str, Any]:
    safe_limit = max(1, min(limit, 100))
    safe_offset = max(0, offset)
    items, total = _service(request).store.list_service_alerts(incidentId, journeyOrderId, safe_limit, safe_offset)
    return {"items": [item.to_json() for item in items], "total": total, "limit": safe_limit, "offset": safe_offset}


@router.post("/recovery-cases/{case_id}/select-option")
def select_option(case_id: str, body: SelectOptionRequest, request: Request) -> dict[str, Any]:
    return _service(request).select_option(case_id, body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.post("/recovery-cases/{case_id}/manual-review/resolve")
def resolve_manual(case_id: str, body: ManualResolveRequest, request: Request) -> dict[str, Any]:
    return _service(request).resolve_manual(case_id, body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.post("/recovery-cases/{case_id}/close")
def close_case(case_id: str, body: CloseCaseRequest, request: Request) -> dict[str, Any]:
    return _service(request).close_case(case_id, body.model_dump(exclude_none=True), _corr(request), _cause(request))
