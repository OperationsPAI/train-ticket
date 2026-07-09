from typing import List, NotRequired, TypedDict


class ServiceProfile(TypedDict):
    service_id: str
    domain: str
    language: str
    phase: str
    work_packages: List[str]
    owns: List[str]
    boundary: NotRequired[str]


SERVICE_PROFILE: ServiceProfile = {"service_id": "identity-verification", "domain": "Identity Verification", "language": "python", "phase": "activation", "work_packages": ["REQ-141"], "owns": ["VerificationCase", "CredentialRecord", "EligibilityCertificate", "PurchaseLimitFact"]}


def health() -> str:
    return "ok"


def profile() -> ServiceProfile:
    return dict(SERVICE_PROFILE)  # type: ignore[return-value]
