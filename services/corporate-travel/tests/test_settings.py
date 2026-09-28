from __future__ import annotations

import json

from corporate_travel.settings import load_onboarding_terms


def test_the_packaged_file_states_the_benchmark_ceiling() -> None:
    """Nothing overrides the packaged file in this checkout, so it is what applies."""
    assert load_onboarding_terms().max_monthly_credit_minor == 50_000_000


def test_a_file_states_the_ceiling_and_a_missing_one_keeps_the_default(tmp_path) -> None:
    written = tmp_path / "onboarding.json"
    written.write_text(
        json.dumps({"onboarding": {"maxMonthlyCreditMinor": 2_000_000}}), encoding="utf-8"
    )
    assert load_onboarding_terms(written).max_monthly_credit_minor == 2_000_000
    assert load_onboarding_terms(tmp_path / "absent.json").max_monthly_credit_minor == 50_000_000
