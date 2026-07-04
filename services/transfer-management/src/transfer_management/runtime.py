from typing import List, NotRequired, TypedDict


class ServiceProfile(TypedDict):
    service_id: str
    domain: str
    language: str
    phase: str
    work_packages: List[str]
    owns: List[str]
    boundary: NotRequired[str]


SERVICE_PROFILE: ServiceProfile = {'service_id': 'transfer-management', 'domain': 'Transfer Management', 'language': 'python', 'phase': 'future-scope', 'work_packages': [], 'owns': ['ConnectionContract', 'MctVersion', 'ProtectedConnection']}


def health() -> str:
    return "ok"


def profile() -> ServiceProfile:
    return dict(SERVICE_PROFILE)  # type: ignore[return-value]
