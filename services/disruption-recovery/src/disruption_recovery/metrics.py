from __future__ import annotations

from typing import Any, Literal, Protocol

from train_ticket_platform.observability import meter


SegmentSignalType = Literal["delay", "cancellation"]
SegmentSignalOutcome = Literal["recovery_created", "zero_impact", "retry", "invalid"]
SegmentSignalSource = Literal["fulfillment", "provider", "other"]

_SIGNAL_TYPES = frozenset({"delay", "cancellation"})
_OUTCOMES = frozenset({"recovery_created", "zero_impact", "retry", "invalid"})
_SOURCES = frozenset({"fulfillment", "provider", "other"})


class SegmentSignalMetricRecorder(Protocol):
    def record(
        self,
        signal_type: SegmentSignalType,
        outcome: SegmentSignalOutcome,
        source: SegmentSignalSource,
    ) -> None: ...


class NoopSegmentSignalMetrics:
    def record(
        self,
        signal_type: SegmentSignalType,
        outcome: SegmentSignalOutcome,
        source: SegmentSignalSource,
    ) -> None:
        return None


class OpenTelemetrySegmentSignalMetrics:
    """Records only the bounded classification of an inbound segment signal."""

    def __init__(self, otel_meter: Any) -> None:
        self._counter = otel_meter.create_counter(
            "train_ticket.disruption.segment_signals",
            unit="{signal}",
            description="Segment disruption signals by type, processing outcome, and bounded source.",
        )

    def record(
        self,
        signal_type: SegmentSignalType,
        outcome: SegmentSignalOutcome,
        source: SegmentSignalSource,
    ) -> None:
        if signal_type not in _SIGNAL_TYPES:
            raise ValueError("unsupported segment signal type")
        if outcome not in _OUTCOMES:
            raise ValueError("unsupported segment signal outcome")
        if source not in _SOURCES:
            raise ValueError("unsupported segment signal source")
        self._counter.add(
            1,
            {
                "signal_type": signal_type,
                "outcome": outcome,
                "source": source,
            },
        )


def segment_signal_metrics_from_environment() -> SegmentSignalMetricRecorder:
    configured_meter = meter("disruption-recovery")
    if configured_meter is None:
        return NoopSegmentSignalMetrics()
    return OpenTelemetrySegmentSignalMetrics(configured_meter)


def segment_signal_type(event_type: str) -> SegmentSignalType | None:
    if event_type == "SegmentDelayed":
        return "delay"
    if event_type == "SegmentCancelled":
        return "cancellation"
    return None


def segment_signal_source(producer: str | None) -> SegmentSignalSource:
    if producer == "fulfillment":
        return "fulfillment"
    if producer == "provider-integration":
        return "provider"
    return "other"
