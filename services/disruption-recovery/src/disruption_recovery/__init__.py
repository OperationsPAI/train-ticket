from typing import List, TypedDict

from fastapi import FastAPI


class ServiceProfile(TypedDict):
    service_id: str
    domain: str
    language: str
    phase: str
    work_packages: List[str]
    owns: List[str]


SERVICE_PROFILE: ServiceProfile = {
    "service_id": "disruption-recovery",
    "domain": "Disruption Recovery",
    "language": "python",
    "phase": "future-scope",
    "work_packages": [],
    "owns": ["RecoveryCase", "RecoveryOption", "CompensationDecision"],
}


def health() -> str:
    return "ok"


def profile() -> ServiceProfile:
    return dict(SERVICE_PROFILE)  # type: ignore[return-value]


def create_app() -> FastAPI:
    app = FastAPI(title="Disruption Recovery", version="0.1.0")

    @app.get("/health")
    def health_endpoint() -> dict[str, object]:
        return {"status": health(), "service": profile()}

    return app
