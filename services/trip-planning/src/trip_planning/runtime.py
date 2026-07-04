from typing import List, NotRequired, TypedDict


class ServiceProfile(TypedDict):
    service_id: str
    domain: str
    language: str
    phase: str
    work_packages: List[str]
    owns: List[str]
    boundary: NotRequired[str]


SERVICE_PROFILE: ServiceProfile = {'service_id': 'trip-planning', 'domain': 'Trip Planning', 'language': 'python', 'phase': 'phase-1-search-foundation', 'work_packages': ['REQ-008-Trip-Planning-search-foundation'], 'owns': ['TripIntent validation', 'Itinerary candidates', 'SearchResult ranking explanations', 'Non-authoritative PriceHint and AvailabilityHint snapshots'], 'boundary': 'Search candidates only: no Offer, CapacityHold, order, payment, ticket, or provider integration.'}


def health() -> str:
    return "ok"


def profile() -> ServiceProfile:
    return dict(SERVICE_PROFILE)  # type: ignore[return-value]
