from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Mapping

from .domain import SelfServiceFacility

CONFIG_DIR_ENV = "CORPORATE_TRAVEL_CONFIG_DIR"
ONBOARDING_CEILING_ENV = "CORPORATE_TRAVEL_ONBOARDING_CEILING_MINOR"
ONBOARDING_DOCUMENT = "onboarding.json"
SHIPPED_CONFIG_DIR = Path(__file__).resolve().parents[2] / "config"


def config_dir() -> Path:
    """The directory this process reads its settings from.

    The image states where the deployment keeps them; a checkout reads the
    directory beside the service's sources, which holds the same document.
    """
    return Path(os.environ.get(CONFIG_DIR_ENV) or SHIPPED_CONFIG_DIR)


def read_settings(document: str) -> Mapping[str, Any]:
    """The settings document this process was started with, or an empty one.

    A document that is absent or unreadable is not an error: the caller then
    keeps the default it compiles, which is what a bare checkout and a unit
    test both get.
    """
    try:
        loaded = json.loads((config_dir() / document).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return loaded if isinstance(loaded, Mapping) else {}


def _declared_ceiling(settings: Mapping[str, Any]) -> int | None:
    section = settings.get("onboarding")
    if not isinstance(section, Mapping):
        return None
    declared = section.get("selfServiceMonthlyCreditCeilingMinor")
    if declared is None:
        return None
    try:
        return int(declared)
    except (TypeError, ValueError):
        return None


def self_service_facility() -> SelfServiceFacility:
    """The facility this deployment opens a self-service agreement under.

    The document the service ships states the benchmark; the environment
    overlays the one field a deployment is underwritten for, so moving it for
    one release does not need a build. A value the document cannot supply and
    the environment does not state leaves the domain's own default in place.
    """
    declared = _declared_ceiling(read_settings(ONBOARDING_DOCUMENT))
    if declared is None:
        declared = SelfServiceFacility().max_monthly_credit_minor
    stated = os.environ.get(ONBOARDING_CEILING_ENV, "").strip()
    if not stated:
        return SelfServiceFacility(max_monthly_credit_minor=declared)
    try:
        return SelfServiceFacility(max_monthly_credit_minor=max(0, int(stated)))
    except ValueError:
        return SelfServiceFacility(max_monthly_credit_minor=declared)
