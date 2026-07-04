from typing import List, NotRequired, TypedDict


class ServiceProfile(TypedDict):
    service_id: str
    domain: str
    language: str
    phase: str
    work_packages: List[str]
    owns: List[str]
    boundary: NotRequired[str]


SERVICE_PROFILE: ServiceProfile = {'service_id': 'fare-pricing', 'domain': 'Fare & Pricing', 'language': 'python', 'phase': 'phase-1-core', 'work_packages': ['REQ-007', 'WP-04'], 'owns': ['FareRuleSet', 'FareRule', 'FareQuote', 'RuleSnapshot', 'FareBreakdown', 'PriceExplanation', 'FeeAssessment', 'AdjustmentQuote']}


def health() -> str:
    return "ok"


def profile() -> ServiceProfile:
    return dict(SERVICE_PROFILE)  # type: ignore[return-value]
