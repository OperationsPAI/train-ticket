from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Any

from train_ticket_platform.events import rfc3339_utc


class DomainError(ValueError):
    pass


class PreconditionFailed(DomainError):
    pass


class VerificationStatus(StrEnum):
    DRAFT = "DRAFT"
    SUBMITTED = "SUBMITTED"
    PASSED = "PASSED"
    FAILED = "FAILED"
    MANUAL_REVIEW_REQUIRED = "MANUAL_REVIEW_REQUIRED"
    EXPIRED = "EXPIRED"
    CANCELLED = "CANCELLED"
    SUPERSEDED = "SUPERSEDED"
    OVERRIDDEN = "OVERRIDDEN"


class CredentialStatus(StrEnum):
    REGISTERED = "REGISTERED"
    PENDING_VERIFICATION = "PENDING_VERIFICATION"
    VERIFIED = "VERIFIED"
    FAILED = "FAILED"
    EXPIRED = "EXPIRED"
    RETIRED = "RETIRED"


class SimOutcome(StrEnum):
    MATCH = "MATCH"
    REJECTED = "REJECTED"
    MANUAL_REVIEW_REQUIRED = "MANUAL_REVIEW_REQUIRED"


class CertificateStatus(StrEnum):
    DRAFT = "DRAFT"
    ACTIVE = "ACTIVE"
    REJECTED = "REJECTED"
    EXPIRED = "EXPIRED"
    REVOKED = "REVOKED"


class PreOrderResult(StrEnum):
    PASS = "PASS"
    REJECT = "REJECT"
    MANUAL_REVIEW_REQUIRED = "MANUAL_REVIEW_REQUIRED"
    DEGRADED = "DEGRADED"


def now_utc() -> datetime:
    return datetime.now(UTC)


def require_text(value: str | None, field_name: str) -> str:
    if value is None or not str(value).strip():
        raise DomainError(f"{field_name} is required")
    return str(value).strip()


def rfc(value: datetime | None) -> str | None:
    return rfc3339_utc(value) if value else None


@dataclass(frozen=True, slots=True)
class CredentialRecord:
    credentialRecordId: str
    travelerId: str
    profileSnapshotVersion: str
    documentType: str
    maskedDocumentNo: str
    documentHash: str
    materialFingerprint: str
    canonicalNameHash: str
    birthDateHash: str | None
    evidenceHash: str | None
    status: CredentialStatus
    validUntil: datetime | None
    identityClusterId: str | None
    verifiedByCaseId: str | None
    createdAt: datetime
    updatedAt: datetime
    version: int = 0

    @classmethod
    def register(cls, *, credential_id: str, traveler_id: str, profile_snapshot_version: str, document_type: str, masked_document_no: str, document_hash: str, material_fingerprint: str, canonical_name_hash: str, birth_date_hash: str | None, valid_until: datetime | None, evidence_hash: str | None, identity_cluster_id: str, at: datetime) -> "CredentialRecord":
        if document_type not in {"ID_CARD", "PASSPORT"}:
            raise DomainError("documentType is invalid")
        if valid_until is not None and valid_until <= at:
            raise DomainError("validUntil must be in the future")
        return cls(require_text(credential_id, "credentialRecordId"), require_text(traveler_id, "travelerId"), require_text(profile_snapshot_version, "profileSnapshotVersion"), document_type, require_text(masked_document_no, "maskedDocumentNo"), require_text(document_hash, "documentHash"), require_text(material_fingerprint, "materialFingerprint"), require_text(canonical_name_hash, "canonicalNameHash"), birth_date_hash, evidence_hash, CredentialStatus.REGISTERED, valid_until, identity_cluster_id, None, at, at)

    def mark_pending(self, at: datetime) -> "CredentialRecord":
        if self.status in {CredentialStatus.RETIRED, CredentialStatus.EXPIRED}:
            raise PreconditionFailed("credential is not verifiable")
        return replace(self, status=CredentialStatus.PENDING_VERIFICATION, updatedAt=at)

    def mark_verified(self, case_id: str, at: datetime) -> "CredentialRecord":
        return replace(self, status=CredentialStatus.VERIFIED, verifiedByCaseId=case_id, updatedAt=at)

    def mark_failed(self, at: datetime) -> "CredentialRecord":
        return replace(self, status=CredentialStatus.FAILED, updatedAt=at)

    def to_json(self) -> dict[str, Any]:
        data = {"credentialRecordId": self.credentialRecordId, "travelerId": self.travelerId, "profileSnapshotVersion": self.profileSnapshotVersion, "documentType": self.documentType, "maskedDocumentNo": self.maskedDocumentNo, "documentHash": self.documentHash, "status": self.status.value, "createdAt": rfc3339_utc(self.createdAt), "updatedAt": rfc3339_utc(self.updatedAt)}
        if self.identityClusterId: data["identityClusterId"] = self.identityClusterId
        if self.validUntil: data["validUntil"] = rfc3339_utc(self.validUntil)
        if self.verifiedByCaseId: data["verifiedByCaseId"] = self.verifiedByCaseId
        return data


@dataclass(frozen=True, slots=True)
class VerificationCase:
    verificationCaseId: str
    travelerId: str
    credentialRecordId: str
    purpose: str
    status: VerificationStatus
    materialFingerprint: str
    simPolicyVersion: str
    simOutcome: SimOutcome | None
    simResultRef: str | None
    reasonCode: str | None
    validFrom: datetime | None
    validUntil: datetime | None
    submittedAt: datetime | None
    completedAt: datetime | None
    createdAt: datetime
    version: int = 0

    @classmethod
    def start(cls, case_id: str, traveler_id: str, credential_id: str, purpose: str, material_fingerprint: str, sim_policy_version: str, at: datetime) -> "VerificationCase":
        if purpose not in {"ORDER_CREATION", "PROFILE_RECHECK", "ELIGIBILITY_CERTIFICATE", "MANUAL_AUDIT"}:
            raise DomainError("purpose is invalid")
        return cls(case_id, traveler_id, credential_id, purpose, VerificationStatus.DRAFT, material_fingerprint, sim_policy_version, None, None, None, None, None, None, None, at)

    def submit_and_record(self, outcome: SimOutcome, sim_result_ref: str, at: datetime) -> "VerificationCase":
        if self.status is not VerificationStatus.DRAFT:
            raise DomainError("verification case is already submitted")
        if outcome is SimOutcome.MATCH:
            return replace(self, status=VerificationStatus.PASSED, simOutcome=outcome, simResultRef=sim_result_ref, validFrom=at, validUntil=at + timedelta(days=365), submittedAt=at, completedAt=at)
        status = VerificationStatus.MANUAL_REVIEW_REQUIRED if outcome is SimOutcome.MANUAL_REVIEW_REQUIRED else VerificationStatus.FAILED
        reason = "MANUAL_REVIEW_REQUIRED" if outcome is SimOutcome.MANUAL_REVIEW_REQUIRED else "NAME_DOCUMENT_MISMATCH"
        return replace(self, status=status, simOutcome=outcome, simResultRef=sim_result_ref, reasonCode=reason, submittedAt=at, completedAt=at)

    def manual_override(self, at: datetime) -> "VerificationCase":
        if self.status not in {VerificationStatus.MANUAL_REVIEW_REQUIRED, VerificationStatus.FAILED}:
            raise DomainError("only failed or manual cases can be overridden")
        return replace(self, status=VerificationStatus.PASSED, simOutcome=SimOutcome.MATCH, reasonCode=None, validFrom=at, validUntil=at + timedelta(days=365), completedAt=at)

    def to_json(self) -> dict[str, Any]:
        data = {"verificationCaseId": self.verificationCaseId, "travelerId": self.travelerId, "credentialRecordId": self.credentialRecordId, "purpose": self.purpose, "status": self.status.value, "materialFingerprint": self.materialFingerprint, "simPolicyVersion": self.simPolicyVersion, "createdAt": rfc3339_utc(self.createdAt)}
        for key, value in (("simOutcome", self.simOutcome.value if self.simOutcome else None), ("simResultRef", self.simResultRef), ("reasonCode", self.reasonCode), ("validFrom", rfc(self.validFrom)), ("validUntil", rfc(self.validUntil)), ("submittedAt", rfc(self.submittedAt)), ("completedAt", rfc(self.completedAt))):
            if value is not None: data[key] = value
        return data


@dataclass(frozen=True, slots=True)
class EligibilityCertificate:
    eligibilityCertificateId: str
    travelerId: str
    credentialRecordId: str | None
    identityClusterId: str | None
    eligibilityType: str
    status: CertificateStatus
    validFrom: datetime
    validUntil: datetime
    policyYear: str
    policyVersion: str
    annualUsageLimit: int
    annualUsageReserved: int
    annualUsageConfirmed: int
    applicableProductCodes: tuple[str, ...]
    certificateHash: str
    evidenceHash: str
    reasonCode: str | None
    createdAt: datetime
    updatedAt: datetime
    version: int = 0

    @classmethod
    def register(cls, *, certificate_id: str, traveler_id: str, credential_id: str | None, cluster_id: str | None, eligibility_type: str, valid_from: datetime, valid_until: datetime, policy_year: str, policy_version: str, annual_usage_limit: int, product_codes: tuple[str, ...], certificate_hash: str, evidence_hash: str, at: datetime) -> "EligibilityCertificate":
        if not credential_id and not cluster_id:
            raise DomainError("credentialRecordId or identityClusterId is required")
        if eligibility_type not in {"STUDENT", "CHILD", "MILITARY_DISABLED"}:
            raise DomainError("eligibilityType is invalid")
        if valid_until <= valid_from:
            raise DomainError("validUntil must be after validFrom")
        if annual_usage_limit < 1:
            raise DomainError("annualUsageLimit must be positive")
        return cls(certificate_id, traveler_id, credential_id, cluster_id, eligibility_type, CertificateStatus.ACTIVE, valid_from, valid_until, policy_year, policy_version, annual_usage_limit, 0, 0, product_codes, certificate_hash, evidence_hash, None, at, at)

    def is_active_for(self, journey_date: str, product_code: str | None) -> bool:
        day = journey_date
        start = self.validFrom.date().isoformat()
        end = self.validUntil.date().isoformat()
        product_ok = product_code is None or product_code in self.applicableProductCodes
        return self.status is CertificateStatus.ACTIVE and start <= day <= end and product_ok and (self.annualUsageReserved + self.annualUsageConfirmed) < self.annualUsageLimit

    def reserve(self, at: datetime) -> "EligibilityCertificate":
        if self.status is not CertificateStatus.ACTIVE:
            raise PreconditionFailed("certificate is not active")
        if self.annualUsageReserved + self.annualUsageConfirmed >= self.annualUsageLimit:
            raise PreconditionFailed("annual usage limit exhausted")
        return replace(self, annualUsageReserved=self.annualUsageReserved + 1, updatedAt=at, version=self.version + 1)

    def confirm(self, at: datetime) -> "EligibilityCertificate":
        if self.annualUsageReserved <= 0:
            raise PreconditionFailed("no reserved usage to confirm")
        return replace(self, annualUsageReserved=self.annualUsageReserved - 1, annualUsageConfirmed=self.annualUsageConfirmed + 1, updatedAt=at, version=self.version + 1)

    def release(self, at: datetime) -> "EligibilityCertificate":
        if self.annualUsageReserved <= 0:
            raise PreconditionFailed("no reserved usage to release")
        return replace(self, annualUsageReserved=self.annualUsageReserved - 1, updatedAt=at, version=self.version + 1)

    def to_json(self, include_evidence: bool = True) -> dict[str, Any]:
        data = {"eligibilityCertificateId": self.eligibilityCertificateId, "travelerId": self.travelerId, "eligibilityType": self.eligibilityType, "status": self.status.value, "validFrom": rfc3339_utc(self.validFrom), "validUntil": rfc3339_utc(self.validUntil), "policyYear": self.policyYear, "policyVersion": self.policyVersion, "annualUsageLimit": self.annualUsageLimit, "annualUsageReserved": self.annualUsageReserved, "annualUsageConfirmed": self.annualUsageConfirmed, "applicableProductCodes": list(self.applicableProductCodes), "createdAt": rfc3339_utc(self.createdAt), "updatedAt": rfc3339_utc(self.updatedAt), "aggregateVersion": self.version}
        if self.credentialRecordId: data["credentialRecordId"] = self.credentialRecordId
        if self.identityClusterId: data["identityClusterId"] = self.identityClusterId
        if include_evidence: data["evidenceHash"] = self.evidenceHash
        if self.reasonCode: data["reasonCode"] = self.reasonCode
        return data


@dataclass(frozen=True, slots=True)
class PurchaseLimitFact:
    purchaseLimitFactId: str
    scopeType: str
    scopeRef: str
    travelerId: str
    orderIntentId: str
    journeyDate: str
    productCode: str
    segmentRefs: tuple[str, ...]
    limitPolicyVersion: str
    status: str
    recordedAt: datetime
    version: int = 0
    journeyOrderId: str | None = None
    releaseReason: str | None = None
    sourceEventId: str | None = None

    def confirm(self, journey_order_id: str, at: datetime) -> "PurchaseLimitFact":
        if self.status != "RECORDED":
            raise PreconditionFailed("purchase-limit fact is not recorded")
        return replace(self, status="CONFIRMED", journeyOrderId=journey_order_id, version=self.version + 1)

    def release(self, release_reason: str, at: datetime, source_event_id: str | None = None) -> "PurchaseLimitFact":
        if self.status != "RECORDED":
            raise PreconditionFailed("purchase-limit fact is not recorded")
        return replace(self, status="RELEASED", releaseReason=release_reason, sourceEventId=source_event_id, version=self.version + 1)

    def to_json(self) -> dict[str, Any]:
        data = {"purchaseLimitFactId": self.purchaseLimitFactId, "scopeType": self.scopeType, "scopeRef": self.scopeRef, "travelerId": self.travelerId, "orderIntentId": self.orderIntentId, "journeyDate": self.journeyDate, "productCode": self.productCode, "segmentRefs": list(self.segmentRefs), "limitPolicyVersion": self.limitPolicyVersion, "status": self.status, "recordedAt": rfc3339_utc(self.recordedAt)}
        if self.journeyOrderId: data["journeyOrderId"] = self.journeyOrderId
        if self.releaseReason: data["releaseReason"] = self.releaseReason
        if self.sourceEventId: data["sourceEventId"] = self.sourceEventId
        return data
