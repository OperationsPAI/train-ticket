from __future__ import annotations

import json
import os
from functools import lru_cache
from pathlib import Path
from typing import Any, Mapping

from .domain import OnboardingTerms

SETTINGS_PATH_ENV = "CORPORATE_TRAVEL_SETTINGS_PATH"
MAX_MONTHLY_CREDIT_ENV = "CORPORATE_TRAVEL_ONBOARDING_MAX_CREDIT_MINOR"
SHIPPED_SETTINGS_PATH = Path(__file__).resolve().parent / "config" / "onboarding.json"


def _settings_section(payload: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    """One named section of a settings file, empty when it is missing or malformed."""
    section = payload.get(name)
    return section if isinstance(section, Mapping) else {}


def _credit_ceiling(section: Mapping[str, Any]) -> int | None:
    declared = section.get("maxMonthlyCreditMinor")
    if declared is None:
        return None
    try:
        return int(declared)
    except (TypeError, ValueError):
        return None


def load_onboarding_terms(path: str | Path | None = None) -> OnboardingTerms:
    """Read the terms from one settings file, defaulting every missing field.

    A file that is absent or unreadable leaves the compiled defaults in place,
    which is what an isolated checkout and a unit test both get.
    """

    source = Path(path) if path is not None else SHIPPED_SETTINGS_PATH
    try:
        payload = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        payload = {}
    section = _settings_section(payload if isinstance(payload, Mapping) else {}, "onboarding")
    ceiling = _credit_ceiling(section)
    override = os.environ.get(MAX_MONTHLY_CREDIT_ENV)
    if override:
        ceiling = int(override)
    if ceiling is None:
        return OnboardingTerms()
    return OnboardingTerms(max_monthly_credit_minor=max(0, ceiling))


@lru_cache(maxsize=1)
def onboarding_terms() -> OnboardingTerms:
    """The terms this deployment serves: the packaged file, unless the release states one."""

    return load_onboarding_terms(os.getenv(SETTINGS_PATH_ENV) or SHIPPED_SETTINGS_PATH)
