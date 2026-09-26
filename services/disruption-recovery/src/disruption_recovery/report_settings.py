from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any, Final, Mapping

from train_ticket_platform.settings import (
    stated_non_negative_int,
    stated_positive_hours,
    stated_settings,
)

SETTINGS_PATH: Final = Path(__file__).resolve().parent / "config" / "reporting.json"
SETTINGS_ENV: Final = "DISRUPTION_REPORT_SETTINGS"
DEFAULT_REPORTING_WINDOW_DAYS: Final = 45
DEFAULT_OPTION_SET_VALIDITY: Final = timedelta(hours=24)


def stated_report_settings() -> Mapping[str, Any]:
    return stated_settings(SETTINGS_PATH, SETTINGS_ENV)


def reporting_window_days() -> int:
    # The reach of the plan the release was published for. Operations widen it for a
    # holiday peak and narrow it when capacity is committed forward, and the report
    # path reads it rather than deciding for itself which service dates are planned.
    return stated_non_negative_int(stated_report_settings(), "reportingWindowDays", DEFAULT_REPORTING_WINDOW_DAYS)


def option_set_validity() -> timedelta:
    # How long a generated set stays selectable for the traveller who has to choose
    # an option in it, which the report path used to state as its own literal.
    return stated_positive_hours(stated_report_settings(), "optionSetValidityHours", DEFAULT_OPTION_SET_VALIDITY)


def outside_reporting_window(service_date: str, reported_at: datetime) -> bool:
    # A service date that does not parse is left to the report path's own validation.
    try:
        claimed = date.fromisoformat(service_date.strip())
    except ValueError:
        return False
    reported_on = reported_at.astimezone(UTC).date() if reported_at.tzinfo else reported_at.date()
    return claimed > reported_on + timedelta(days=reporting_window_days())
