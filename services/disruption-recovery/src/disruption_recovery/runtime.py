from typing import List, NotRequired, TypedDict


class ServiceProfile(TypedDict):
    service_id: str
    domain: str
    language: str
    phase: str
    work_packages: List[str]
    owns: List[str]
    boundary: NotRequired[str]


SERVICE_PROFILE: ServiceProfile = {'service_id': 'disruption-recovery', 'domain': 'Disruption Recovery', 'language': 'python', 'phase': 'future-scope', 'work_packages': [], 'owns': ['RecoveryCase', 'RecoveryOption', 'CompensationDecision']}


def health() -> str:
    return "ok"


def profile() -> ServiceProfile:
    return dict(SERVICE_PROFILE)  # type: ignore[return-value]
