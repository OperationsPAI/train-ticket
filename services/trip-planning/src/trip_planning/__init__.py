from typing import List, TypedDict

try:  # FastAPI is used in runtime images; tests can run without installed deps.
    from fastapi import FastAPI, HTTPException
except ModuleNotFoundError:  # pragma: no cover - compatibility shim for stdlib-only validation
    class HTTPException(Exception):
        def __init__(self, status_code: int, detail: str) -> None:
            super().__init__(detail)
            self.status_code = status_code
            self.detail = detail

    class _Route:
        def __init__(self, path: str) -> None:
            self.path = path

    class FastAPI:  # type: ignore[no-redef]
        def __init__(self, title: str, version: str) -> None:
            self.title = title
            self.version = version
            self.routes: list[_Route] = []

        def get(self, path: str):
            self.routes.append(_Route(path))
            return lambda func: func

        def post(self, path: str):
            self.routes.append(_Route(path))
            return lambda func: func

from .application import search_itineraries_from_payload
from .domain import (
    AvailabilityHint,
    Itinerary,
    LegCandidate,
    PreferenceConstraints,
    PriceHint,
    TripIntent,
    TripPlanningValidationError,
)


class ServiceProfile(TypedDict):
    service_id: str
    domain: str
    language: str
    phase: str
    work_packages: List[str]
    owns: List[str]
    boundary: str


SERVICE_PROFILE: ServiceProfile = {
    "service_id": "trip-planning",
    "domain": "Trip Planning",
    "language": "python",
    "phase": "phase-1-search-foundation",
    "work_packages": ["REQ-008-Trip-Planning-search-foundation"],
    "owns": [
        "TripIntent validation",
        "Itinerary candidates",
        "SearchResult ranking explanations",
        "Non-authoritative PriceHint and AvailabilityHint snapshots",
    ],
    "boundary": "Search candidates only: no Offer, CapacityHold, order, payment, ticket, or provider integration.",
}


def health() -> str:
    return "ok"


def profile() -> ServiceProfile:
    return dict(SERVICE_PROFILE)  # type: ignore[return-value]


def create_app() -> FastAPI:
    app = FastAPI(title="Trip Planning", version="0.1.0")

    @app.get("/health")
    def health_endpoint() -> dict[str, object]:
        return {"status": health(), "service": profile()}

    @app.post("/search")
    def search_endpoint(payload: dict[str, object]) -> dict[str, object]:
        try:
            return search_itineraries_from_payload(payload)
        except TripPlanningValidationError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    return app


__all__ = [
    "AvailabilityHint",
    "Itinerary",
    "LegCandidate",
    "PreferenceConstraints",
    "PriceHint",
    "TripIntent",
    "TripPlanningValidationError",
    "create_app",
    "health",
    "profile",
    "search_itineraries_from_payload",
]
