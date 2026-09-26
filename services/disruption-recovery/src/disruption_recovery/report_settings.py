"""The report settings this release is deployed with.

A report names the service date it affects, and the recovery the platform can offer for
that date is bounded by the inventory and the capacity the release holds for it. Those are
release decisions rather than properties of the report path, so the release states them in
the settings file the service is packaged with and the report path reads them.

The reporting window is the reach of the plan: the services the release has planned for.
Operations widen it for a holiday peak and narrow it when capacity is committed forward,
without touching the report path. The option set validity is how long a generated set stays
selectable for the traveller who has to choose an option.
"""

from __future__ import annotations

import json
import os
from datetime import date, datetime, timedelta
from functools import lru_cache
from pathlib import Path
from typing import Any, Final, Mapping

SETTINGS_PATH: Final = Path(__file__).resolve().parent / "config" / "reporting.json"
SETTINGS_ENV: Final = "DISRUPTION_REPORT_SETTINGS"
DEFAULT_REPORTING_WINDOW_DAYS: Final = 45
DEFAULT_OPTION_SET_VALIDITY: Final = timedelta(hours=24)


def _stated_settings() -> Mapping[str, Any]:
    configured = os.getenv(SETTINGS_ENV, "").strip()
    path = Path(configured) if configured else SETTINGS_PATH
    try:
        stated = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return stated if isinstance(stated, Mapping) else {}


@lru_cache(maxsize=1)
def reporting_window_days() -> int:
    """How many days ahead of the report the release plans recovery for."""
    try:
        days = int(_stated_settings().get("reportingWindowDays"))
    except (TypeError, ValueError):
        return DEFAULT_REPORTING_WINDOW_DAYS
    return days if days >= 0 else DEFAULT_REPORTING_WINDOW_DAYS


@lru_cache(maxsize=1)
def option_set_validity() -> timedelta:
    """How long a generated option set stays selectable in this release."""
    try:
        hours = int(_stated_settings().get("optionSetValidityHours"))
    except (TypeError, ValueError):
        return DEFAULT_OPTION_SET_VALIDITY
    return timedelta(hours=hours) if hours > 0 else DEFAULT_OPTION_SET_VALIDITY


def outside_reporting_window(service_date: str, reported_at: datetime) -> bool:
    """Whether a report names a service date this release holds no plan for.

    A service date that does not parse is left to the report path's own validation.
    """
    try:
        claimed = date.fromisoformat(service_date.strip())
    except ValueError:
        return False
    return claimed > reported_at.date() + timedelta(days=reporting_window_days())
