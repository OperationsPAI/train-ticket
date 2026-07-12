from typing import List, NotRequired, TypedDict


class ServiceProfile(TypedDict):
    service_id: str
    domain: str
    language: str
    phase: str
    work_packages: List[str]
    owns: List[str]
    boundary: NotRequired[str]


SERVICE_PROFILE: ServiceProfile = {"service_id": "disruption-recovery", "domain": "Disruption Recovery", "language": "python", "phase": "activation", "work_packages": ["REQ-117"], "owns": ["Incident", "RecoveryCase", "RecoveryOptionSet"]}


def health() -> str:
    return "ok"


def profile() -> ServiceProfile:
    return dict(SERVICE_PROFILE)  # type: ignore[return-value]
