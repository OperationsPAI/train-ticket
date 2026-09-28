from __future__ import annotations

from pathlib import Path

from corporate_travel.domain import SelfServiceFacility
from corporate_travel.settings import (
    CONFIG_DIR_ENV,
    ONBOARDING_CEILING_ENV,
    read_settings,
    self_service_facility,
)


def test_the_document_the_service_ships_states_the_benchmark(monkeypatch) -> None:
    monkeypatch.delenv(ONBOARDING_CEILING_ENV, raising=False)
    monkeypatch.delenv(CONFIG_DIR_ENV, raising=False)
    assert self_service_facility() == SelfServiceFacility(50_000_000)


def test_the_environment_states_the_facility_a_deployment_is_underwritten_for(monkeypatch) -> None:
    monkeypatch.setenv(ONBOARDING_CEILING_ENV, "750000")
    assert self_service_facility() == SelfServiceFacility(750_000)


def test_a_document_that_is_not_there_leaves_the_domain_default(monkeypatch) -> None:
    monkeypatch.setenv(CONFIG_DIR_ENV, "/no/such/config/dir")
    monkeypatch.delenv(ONBOARDING_CEILING_ENV, raising=False)
    assert read_settings("onboarding.json") == {}
    assert self_service_facility() == SelfServiceFacility()


def test_a_malformed_statement_falls_back_to_the_document(monkeypatch) -> None:
    monkeypatch.delenv(CONFIG_DIR_ENV, raising=False)
    monkeypatch.setenv(ONBOARDING_CEILING_ENV, "one million")
    assert self_service_facility() == SelfServiceFacility(50_000_000)


def test_a_document_that_is_not_an_object_is_ignored(tmp_path: Path, monkeypatch) -> None:
    (tmp_path / "onboarding.json").write_text("[1, 2, 3]", encoding="utf-8")
    monkeypatch.setenv(CONFIG_DIR_ENV, str(tmp_path))
    monkeypatch.delenv(ONBOARDING_CEILING_ENV, raising=False)
    assert read_settings("onboarding.json") == {}
