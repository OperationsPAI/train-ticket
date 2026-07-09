from __future__ import annotations

from typing import Any
from fastapi import APIRouter, Query, Request, Response
from pydantic import BaseModel, ConfigDict, Field
from identity_verification.application.service import IdentityVerificationService

router = APIRouter(prefix="/api/v1/identity-verification")


class LooseModel(BaseModel):
    model_config = ConfigDict(extra="allow")


class RegisterCredentialRequest(LooseModel):
    travelerId: str
    profileSnapshotVersion: str
    documentType: str
    maskedDocumentNo: str
    documentHash: str
    canonicalNameHash: str
    birthDateHash: str | None = None
    validUntil: str | None = None
    evidenceHash: str | None = None


class StartVerificationRequest(LooseModel):
    travelerId: str
    credentialRecordId: str
    purpose: str
    materialFingerprint: str
    simPolicyVersion: str
    requestedAt: str


class RegisterCertificateRequest(LooseModel):
    travelerId: str
    credentialRecordId: str | None = None
    identityClusterId: str | None = None
    eligibilityType: str
    validFrom: str
    validUntil: str
    policyYear: str
    policyVersion: str
    annualUsageLimit: int = Field(gt=0)
    applicableProductCodes: list[str] = Field(min_length=1)
    certificateHash: str
    evidenceHash: str


class PreOrderCheckRequest(LooseModel):
    orderIntentId: str
    accountId: str
    offerId: str
    offerVersion: int
    travelerRefs: list[str] = Field(min_length=1)
    segmentRefs: list[str] = Field(min_length=1)
    journeyDate: str
    productCode: str
    requestedEligibilityTypes: list[str] | None = None
    limitPolicyVersion: str
    requestedAt: str


def _service(request: Request) -> IdentityVerificationService:
    return request.app.state.identity_verification_service


def _corr(request: Request) -> str:
    return str(getattr(request.state, "correlation_id", None) or request.headers.get("X-Correlation-Id") or request.headers.get("X-Correlation-ID") or "corr-identity-verification")


def _cause(request: Request) -> str:
    idem = getattr(request.state, "idempotency_decision", None)
    return str(getattr(idem, "key", None) or request.headers.get("Idempotency-Key") or getattr(request.state, "request_id", "cmd-identity-verification"))


@router.post("/credentials", status_code=201)
def register_credential(body: RegisterCredentialRequest, request: Request) -> dict[str, Any]:
    return _service(request).register_credential(body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.post("/verification-cases", status_code=201)
def start_verification_case(body: StartVerificationRequest, request: Request) -> dict[str, Any]:
    return _service(request).start_verification_case(body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.get("/verification-cases/{verification_case_id}")
def get_verification_case(verification_case_id: str, request: Request) -> dict[str, Any]:
    return _service(request).store.get_case(verification_case_id).to_json()


@router.post("/verification-cases/{verification_case_id}/manual-override")
def manual_override(verification_case_id: str, request: Request) -> dict[str, Any]:
    return _service(request).manual_override(verification_case_id, _corr(request), _cause(request))


@router.get("/credentials/{credential_record_id}/verification-status")
def credential_status(credential_record_id: str, request: Request) -> dict[str, Any]:
    return _service(request).credential_status(credential_record_id)


@router.post("/eligibility-certificates", status_code=201)
def register_certificate(body: RegisterCertificateRequest, request: Request) -> dict[str, Any]:
    return _service(request).register_certificate(body.model_dump(exclude_none=True), _corr(request), _cause(request))


@router.get("/eligibility-certificates")
def query_certificates(request: Request, travelerId: str = Query(...), eligibilityType: str | None = None, journeyDate: str = Query(...), productCode: str | None = None, limit: int = 20, offset: int = 0) -> dict[str, Any]:
    return _service(request).query_certificates(travelerId, eligibilityType, journeyDate, productCode, max(1, min(limit, 100)), max(0, offset))


@router.post("/pre-order-checks")
def pre_order_check(body: PreOrderCheckRequest, request: Request, response: Response) -> dict[str, Any]:
    result, created = _service(request).pre_order_check(body.model_dump(exclude_none=True), _corr(request), _cause(request))
    response.status_code = 201 if created else 200
    return result
