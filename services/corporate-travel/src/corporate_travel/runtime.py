from __future__ import annotations


def health() -> str:
    return "ok"


def profile() -> dict[str, str]:
    return {
        "service_id": "corporate-travel",
        "domain": "Corporate Travel",
        "status": "REQ-215 service foundation",
        "language": "python",
    }
