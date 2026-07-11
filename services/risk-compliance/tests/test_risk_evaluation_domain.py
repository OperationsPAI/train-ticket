from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from risk_compliance.domain import RiskComplianceError, RiskScoreCalculator, RiskSignal, RiskVerdict, VelocityDimension, VelocityRule
from risk_compliance.evaluation import InMemoryVelocityCounter


def test_risk_score_calculator_weights_signals_to_100_point_score() -> None:
    score = RiskScoreCalculator().calculate(
        (
            RiskSignal("velocity_score", "near-limit", 50),
            RiskSignal("account_age_score", "new", 100),
            RiskSignal("high_value_order", 600_000, 100),
        )
    )

    assert score == 40


def test_risk_score_calculator_clamps_and_ignores_unknown_signals() -> None:
    score = RiskScoreCalculator({"velocity_score": 80, "known_scalper_pattern": 80}).calculate(
        (
            RiskSignal("velocity_score", "breach", 100),
            RiskSignal("known_scalper_pattern", "bulk", 100),
            RiskSignal("unknown", "ignored", 100),
        )
    )

    assert score == 100


def test_risk_signal_rejects_invalid_normalized_score() -> None:
    with pytest.raises(RiskComplianceError):
        RiskSignal("velocity_score", "bad", 101)


def test_velocity_rule_blocks_only_when_count_exceeds_threshold() -> None:
    rule = VelocityRule("VELOCITY_ACCOUNT_ORDER", VelocityDimension.ACCOUNT, 3, 300, RiskVerdict.BLOCK)

    assert rule.evaluate(3).result is RiskVerdict.PASS
    breached = rule.evaluate(4)
    assert breached.result is RiskVerdict.BLOCK
    assert breached.detail == "4/3 in window"


def test_velocity_rule_validates_threshold_window_and_action() -> None:
    with pytest.raises(RiskComplianceError):
        VelocityRule("bad", VelocityDimension.ACCOUNT, 0, 300, RiskVerdict.BLOCK)
    with pytest.raises(RiskComplianceError):
        VelocityRule("bad", VelocityDimension.ACCOUNT, 3, 0, RiskVerdict.BLOCK)
    with pytest.raises(RiskComplianceError):
        VelocityRule("bad", VelocityDimension.ACCOUNT, 3, 300, RiskVerdict.PASS)


def test_in_memory_velocity_counter_evicts_entries_outside_sliding_window() -> None:
    counter = InMemoryVelocityCounter()
    base = datetime(2026, 7, 5, 10, 0, tzinfo=UTC)

    assert counter.increment_and_count(VelocityDimension.ACCOUNT, "acct", base, 300) == 1
    assert counter.increment_and_count(VelocityDimension.ACCOUNT, "acct", base + timedelta(minutes=4), 300) == 2
    assert counter.increment_and_count(VelocityDimension.ACCOUNT, "acct", base + timedelta(minutes=6), 300) == 2
