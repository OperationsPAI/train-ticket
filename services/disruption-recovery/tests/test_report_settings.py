import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

from disruption_recovery.report_settings import (
    outside_reporting_window,
    reporting_window_days,
)

SETTINGS_PATH = Path(__file__).parents[1] / "src" / "disruption_recovery" / "config" / "reporting.json"


def _reported_at() -> datetime:
    return datetime(2026, 9, 28, 12, 0, tzinfo=UTC)


def test_the_reporting_window_is_the_release_value():
    stated = json.loads(SETTINGS_PATH.read_text(encoding="utf-8"))["reportingWindowDays"]

    assert reporting_window_days() == stated


def test_a_service_date_beyond_the_window_is_outside_it():
    reported_at = _reported_at()
    boundary = reported_at.date() + timedelta(days=reporting_window_days())

    assert outside_reporting_window(boundary.isoformat(), reported_at) is False
    assert outside_reporting_window((boundary + timedelta(days=1)).isoformat(), reported_at) is True


def test_a_service_date_inside_the_window_is_planned_for():
    reported_at = _reported_at()

    assert outside_reporting_window(reported_at.date().isoformat(), reported_at) is False
    assert outside_reporting_window((reported_at.date() - timedelta(days=3)).isoformat(), reported_at) is False


def test_an_unreadable_service_date_is_left_to_the_report_path():
    assert outside_reporting_window("", _reported_at()) is False
    assert outside_reporting_window("the second of August", _reported_at()) is False
