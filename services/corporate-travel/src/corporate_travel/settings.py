from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Mapping

from .domain import CreditFacility

SETTINGS_PATH_ENV = "CORPORATE_TRAVEL_SETTINGS_PATH"
ONBOARDING_MAX_CREDIT_ENV = "CORPORATE_TRAVEL_ONBOARDING_MAX_CREDIT_MINOR"
SHIPPED_SETTINGS_PATH = Path(__file__).resolve().parent / "config" / "onboarding.json"


def read_settings(path: str | Path | None = None) -> Mapping[str, Any]:
    """The settings document this process was started with, or an empty one.

    A file that is absent or unreadable is not an error: every caller then keeps
    the default it compiles, which is what a bare checkout and a unit test both
    get.
    """

    source = Path(path) if path is not None else Path(os.getenv(SETTINGS_PATH_ENV) or SHIPPED_SETTINGS_PATH)
    try:
        document = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return document if isinstance(document, Mapping) else {}


def _declared_credit_ceiling(document: Mapping[str, Any]) -> int | None:
    section = document.get("onboarding")
    if not isinstance(section, Mapping):
        return None
    declared = section.get("maxMonthlyCreditMinor")
    if declared is None:
        return None
    try:
        return int(declared)
    except (TypeError, ValueError):
        return None


def onboarding_facility() -> CreditFacility:
    """The facility this deployment opens a self-service agreement with.

    The packaged `config/onboarding.json` states what the service ships; the
    environment overlays any field of it, so a release states the facility it is
    approved for without a rebuild.
    """

    ceiling = _declared_credit_ceiling(read_settings())
    override = os.getenv(ONBOARDING_MAX_CREDIT_ENV)
    if override:
        ceiling = int(override)
    if ceiling is None:
        return CreditFacility()
    return CreditFacility(self_service_max_monthly_minor=max(0, ceiling))
