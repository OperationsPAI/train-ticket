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


def _named_section(name: str, path: str | Path | None = None) -> Mapping[str, Any]:
    """One named section of the settings document, or an empty mapping."""

    section = read_settings(path).get(name)
    return section if isinstance(section, Mapping) else {}


def _as_int(value: object) -> int | None:
    """A number the file states as JSON and the environment states as text."""

    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    try:
        return int(text)
    except ValueError:
        return None


def onboarding_facility() -> CreditFacility:
    """The facility this deployment opens a self-service agreement with.

    The packaged `config/onboarding.json` states what the service ships; the
    environment overlays any field of it, so a release states the facility it is
    approved for without a rebuild.
    """

    ceiling = _as_int(_named_section("onboarding").get("maxMonthlyCreditMinor"))
    override = os.getenv(ONBOARDING_MAX_CREDIT_ENV)
    if override:
        ceiling = int(override)
    if ceiling is None:
        return CreditFacility()
    return CreditFacility(self_service_max_monthly_minor=max(0, ceiling))
