from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Query, Request
from pydantic import BaseModel, ConfigDict, Field

from transfer_management.application.service import TransferManagementService

router = APIRouter(prefix="/api/v1")


class LooseModel(BaseModel):
    model_config = ConfigDict(extra="allow")


class CreatePlanRequest(LooseModel):
    itineraryRef: str
    planningSnapshotVersion: int
    journeyOrderId: str | None = None
    travelerRefs: list[str] = Field(min_length=1)
    connectionIntents: list[dict[str, Any]] | None = None
    expiresAt: str | None = None


class EvaluatePlanRequest(LooseModel):
    planningSnapshotVersion: int
    asOf: str | None = None
    reason: str | None = None


class RegisterConnectionRequest(LooseModel):
    transferPlanId: str
    itineraryRef: str
    previousSegmentRef: str
    nextSegmentRef: str
    travelerRefs: list[str] = Field(min_length=1)
    fromNodeRef: str
    toNodeRef: str
    fromNodeType: str
    toNodeType: str
    transferCategory: str
    contractId: str
    contractType: str
    window: dict[str, Any]
    journeyOrderId: str | None = None
    serviceDate: str | None = None
    scheduledServiceRef: str | None = None


class ReaccommodateConnectionRequest(LooseModel):
    caseId: str
    replacementWindow: dict[str, Any]


class SegmentReportRequest(LooseModel):
    segmentRef: str
    reportType: str
    reportedBy: dict[str, Any]
    sourceSystem: str
    sourceRecordId: str
    observedAt: str
    estimatedArrivalAt: str | None = None
    actualArrivalAt: str | None = None
    cancelledAt: str | None = None
    reason: str | None = None


class ContractRequest(LooseModel):
    connectionId: str
    contractType: str
    responsibleParty: str
    coverageSummary: str
    disclosureVersion: str
    termsSnapshotRef: str | None = None


class ConfirmContractRequest(LooseModel):
    acceptedByRef: str
    acceptedVersion: str
    confirmedAt: str | None = None


class WithdrawContractRequest(LooseModel):
    withdrawnBy: dict[str, Any]
    reason: str


class MctRuleRequest(LooseModel):
    fromNodeType: str
    toNodeType: str
    transferCategory: str
    minimumMinutes: int
    conditions: dict[str, Any]
    validFrom: str
    validUntil: str | None = None


class PublishRuleRequest(LooseModel):
    publishedBy: dict[str, Any]
    publishReason: str


class RetireRuleRequest(LooseModel):
    retiredBy: dict[str, Any]
    retireReason: str
    retiredAt: str | None = None


class RiskPolicyRequest(LooseModel):
    version: str
    thresholds: dict[str, Any]
    createdBy: dict[str, Any]


class ActivateRiskPolicyRequest(LooseModel):
    activatedBy: dict[str, Any]
    activateReason: str | None = None


def _service(request: Request) -> TransferManagementService:
    return request.app.state.transfer_management_service


def _corr(request: Request) -> str:
    return str(getattr(request.state, "correlation_id", None) or request.headers.get("X-Correlation-Id") or request.headers.get("X-Correlation-ID") or "corr-transfer-management")


def _cause(request: Request) -> str:
    idem = getattr(request.state, "idempotency_decision", None)
    return str(getattr(idem, "key", None) or request.headers.get("Idempotency-Key") or getattr(request.state, "request_id", "cmd-transfer-management"))


@router.post("/transfer-plans", status_code=201)
def create_plan(body: CreatePlanRequest, request: Request) -> dict[str, Any]:
    return _service(request).create_plan(body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.post("/transfer-plans/{plan_id}/evaluate")
def evaluate_plan(plan_id: str, body: EvaluatePlanRequest, request: Request) -> dict[str, Any]:
    return _service(request).evaluate_plan(plan_id, body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.post("/transfer-plans/{plan_id}/refresh")
def refresh_plan(plan_id: str, body: EvaluatePlanRequest, request: Request) -> dict[str, Any]:
    return _service(request).evaluate_plan(plan_id, body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.post("/transfer-plans/{plan_id}/expire")
def expire_plan(plan_id: str, body: LooseModel, request: Request) -> dict[str, Any]:
    return _service(request).expire_plan(plan_id, body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.get("/transfer-plans/{plan_id}")
def get_plan(plan_id: str, request: Request) -> dict[str, Any]:
    return _service(request).get_plan(plan_id)


@router.post("/connections", status_code=201)
def register_connection(body: RegisterConnectionRequest, request: Request) -> dict[str, Any]:
    return _service(request).register_connection(body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.get("/connections/{connection_id}")
def get_connection(connection_id: str, request: Request) -> dict[str, Any]:
    return _service(request).get_connection(connection_id)


@router.post("/connections/{connection_id}/reaccommodate")
def reaccommodate_connection(connection_id: str, body: ReaccommodateConnectionRequest, request: Request) -> dict[str, Any]:
    return _service(request).reaccommodate_connection(connection_id, body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.get("/connections")
def list_connections(request: Request, journeyOrderId: str = Query(...)) -> dict[str, Any]:
    return _service(request).list_connections_by_journey(journeyOrderId)


@router.post("/segment-status-reports", status_code=202)
def report_segment_status(body: SegmentReportRequest, request: Request) -> dict[str, Any]:
    return _service(request).report_segment_status(body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.post("/connection-contracts", status_code=201)
def propose_contract(body: ContractRequest, request: Request) -> dict[str, Any]:
    return _service(request).propose_contract(body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.post("/connection-contracts/{contract_id}/confirm")
def confirm_contract(contract_id: str, body: ConfirmContractRequest, request: Request) -> dict[str, Any]:
    return _service(request).confirm_contract(contract_id, body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.post("/connection-contracts/{contract_id}/withdraw")
def withdraw_contract(contract_id: str, body: WithdrawContractRequest, request: Request) -> dict[str, Any]:
    return _service(request).withdraw_contract(contract_id, body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.post("/mct-rules", status_code=201)
def create_mct_rule(body: MctRuleRequest, request: Request) -> dict[str, Any]:
    return _service(request).create_mct_rule(body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.patch("/mct-rules/{rule_id}")
def update_mct_rule(rule_id: str, body: LooseModel, request: Request) -> dict[str, Any]:
    return _service(request).update_mct_rule(rule_id, body.model_dump(exclude_none=True))


@router.post("/mct-rules/{rule_id}/publish")
def publish_mct_rule(rule_id: str, body: PublishRuleRequest, request: Request) -> dict[str, Any]:
    return _service(request).publish_mct_rule(rule_id, body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.post("/mct-rules/{rule_id}/retire")
def retire_mct_rule(rule_id: str, body: RetireRuleRequest, request: Request) -> dict[str, Any]:
    return _service(request).retire_mct_rule(rule_id, body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.get("/mct-rules")
def list_mct_rules(request: Request, fromNodeType: str | None = None, toNodeType: str | None = None, transferCategory: str | None = None, status: str | None = None, limit: int = 20, offset: int = 0) -> dict[str, Any]:
    return _service(request).list_mct_rules({"fromNodeType": fromNodeType, "toNodeType": toNodeType, "transferCategory": transferCategory, "status": status, "limit": limit, "offset": offset})


@router.post("/risk-policies", status_code=201)
def create_risk_policy(body: RiskPolicyRequest, request: Request) -> dict[str, Any]:
    return _service(request).create_risk_policy(body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.post("/risk-policies/{policy_id}/activate")
def activate_risk_policy(policy_id: str, body: ActivateRiskPolicyRequest, request: Request) -> dict[str, Any]:
    return _service(request).activate_risk_policy(policy_id, body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.get("/risk-policies/active")
def get_active_risk_policy(request: Request) -> dict[str, Any]:
    return _service(request).get_active_risk_policy()
