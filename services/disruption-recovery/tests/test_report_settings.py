from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

from disruption_recovery.report_settings import outside_reporting_window, reporting_window_days

SETTINGS_PATH = Path(__file__).parents[1] / "src" / "disruption_recovery" / "config" / "reporting.json"


def stated_window_days() -> int:
    return int(json.loads(SETTINGS_PATH.read_text(encoding="utf-8"))["reportingWindowDays"])


def reported_at() -> datetime:
    return datetime(2026, 9, 28, 12, 0, tzinfo=UTC)


def test_the_reader_answers_the_window_the_release_states() -> None:
    assert reporting_window_days() == stated_window_days()


def test_the_boundary_day_is_inside_the_window_and_the_next_day_is_not() -> None:
    at = reported_at()
    boundary = at.date() + timedelta(days=reporting_window_days())

    assert outside_reporting_window(boundary.isoformat(), at) is False
    assert outside_reporting_window((boundary + timedelta(days=1)).isoformat(), at) is True


def test_a_service_date_the_release_has_reached_is_inside_the_window() -> None:
    at = reported_at()

    assert outside_reporting_window(at.date().isoformat(), at) is False
    assert outside_reporting_window((at.date() - timedelta(days=3)).isoformat(), at) is False


def test_a_service_date_that_does_not_parse_is_left_to_the_report_path() -> None:
    at = reported_at()

    assert outside_reporting_window("", at) is False
    assert outside_reporting_window("the second of August", at) is False
