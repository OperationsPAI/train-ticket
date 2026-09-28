from __future__ import annotations

import json

from corporate_travel.settings import (
    ONBOARDING_MAX_CREDIT_ENV,
    SETTINGS_PATH_ENV,
    onboarding_facility,
    read_settings,
)


def test_the_packaged_file_states_the_benchmark_facility() -> None:
    """A checkout with no settings file and no release override gets what ships."""

    assert onboarding_facility().self_service_max_monthly_minor == 50_000_000


def test_the_release_overlays_the_packaged_file(tmp_path, monkeypatch) -> None:
    path = tmp_path / "onboarding.json"
    path.write_text(json.dumps({"onboarding": {"maxMonthlyCreditMinor": 900_000}}), encoding="utf-8")
    monkeypatch.setenv(SETTINGS_PATH_ENV, str(path))

    assert onboarding_facility().self_service_max_monthly_minor == 900_000

    monkeypatch.setenv(ONBOARDING_MAX_CREDIT_ENV, "700000")

    assert onboarding_facility().self_service_max_monthly_minor == 700_000


def test_a_file_that_is_not_there_keeps_the_shipped_default(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv(SETTINGS_PATH_ENV, str(tmp_path / "absent.json"))
    monkeypatch.delenv(ONBOARDING_MAX_CREDIT_ENV, raising=False)

    assert read_settings() == {}
    assert onboarding_facility().self_service_max_monthly_minor == 50_000_000
