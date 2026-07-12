from typing import List, NotRequired, TypedDict


class ServiceProfile(TypedDict):
    service_id: str
    domain: str
    language: str
    phase: str
    work_packages: List[str]
    owns: List[str]
    boundary: NotRequired[str]


SERVICE_PROFILE: ServiceProfile = {
    "service_id": "legacy-acl",
    "domain": "Legacy ACL",
    "language": "python",
    "phase": "phase-1-core",
    "work_packages": ["REQ-071", "WP-23"],
    "owns": [],
    "boundary": "Strangler facade; owns no domain state",
}


def health() -> str:
    return "ok"


def profile() -> ServiceProfile:
    return dict(SERVICE_PROFILE)  # type: ignore[return-value]
