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
    'service_id': 'reporting',
    'domain': 'Reporting',
    'language': 'python',
    'phase': 'phase-1-limited',
    'work_packages': ['REQ-025', 'WP-22'],
    'owns': [
        'MetricDefinition',
        'DashboardReadModel',
        'FunnelView',
        'ConsumedEventLog',
    ],
}


def health() -> str:
    return "ok"


def profile() -> ServiceProfile:
    return dict(SERVICE_PROFILE)  # type: ignore[return-value]
