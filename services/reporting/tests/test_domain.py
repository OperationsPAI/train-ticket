from __future__ import annotations

import unittest
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from reporting import (
    ConsumedEventLog,
    AnomalyDetector,
    AnomalySeverity,
    MetricAggregator,
    OperationalEvent,
    ConsumedEventRecord,
    ConsumptionStatus,
    DashboardReadModel,
    EventMetadata,
    FunnelStep,
    FunnelStepCount,
    FunnelView,
    ContextEventRollup,
    ContextCountItem,
    MetricCategory,
    MetricDefinition,
    MetricGranularity,
    MetricRef,
    MetricStatus,
    Money,
    ReadModelSnapshot,
    ReadModelStatus,
    ReportingError,
    define_metric,
    project_event,
    publish_metric_version,
    rebuild_read_model,
)

NOW = datetime(2026, 7, 3, 12, 0, tzinfo=UTC)


def sample_metadata(event_id: str = "evt-001") -> EventMetadata:
    return EventMetadata(
        event_id=event_id,
        occurred_at=NOW,
        source_command_id="cmd-001",
        causation_id="cause-001",
        correlation_id="corr-001",
    )


class MoneyTest(unittest.TestCase):
    def test_currency_validation(self) -> None:
        Money("100.00", "USD")
        Money("50.00", "CNY")
        with self.assertRaisesRegex(ReportingError, "three-letter ISO"):
            Money("100.00", "US")
        with self.assertRaisesRegex(ReportingError, "three-letter ISO"):
            Money("100.00", "USDD")

    def test_arithmetic_enforces_same_currency(self) -> None:
        a = Money("100.00", "USD")
        b = Money("50.00", "USD")
        self.assertEqual(a + b, Money("150.00", "USD"))
        self.assertEqual(a - b, Money("50.00", "USD"))
        with self.assertRaisesRegex(ReportingError, "currency mismatch"):
            a + Money("50.00", "CNY")


class EventMetadataTest(unittest.TestCase):
    def test_requires_event_id_and_source_command_id(self) -> None:
        with self.assertRaisesRegex(ReportingError, "event_id is required"):
            EventMetadata(
                event_id="",
                occurred_at=NOW,
                source_command_id="cmd-001",
                causation_id="c1",
                correlation_id="c2",
            )
        with self.assertRaisesRegex(ReportingError, "source_command_id"):
            EventMetadata(
                event_id="e1",
                occurred_at=NOW,
                source_command_id="",
                causation_id="c1",
                correlation_id="c2",
            )


class ConsumedEventRecordTest(unittest.TestCase):
    def test_requires_event_id_and_source(self) -> None:
        with self.assertRaisesRegex(ReportingError, "event id is required"):
            ConsumedEventRecord(
                event_id="",
                consumed_at=NOW,
                source="journey-order",
                event_type="JourneyOrderCreated",
            )
        with self.assertRaisesRegex(ReportingError, "source is required"):
            ConsumedEventRecord(
                event_id="evt-1",
                consumed_at=NOW,
                source="",
                event_type="JourneyOrderCreated",
            )


class MetricDefinitionTest(unittest.TestCase):
    def test_create_draft_metric_via_define_metric_command(self) -> None:
        metric = define_metric(
            metric_id="metric-revenue",
            name="Total Revenue",
            description="Sum of captured payments",
            owner="finance-team",
            category=MetricCategory.FINANCIAL,
            granularity=MetricGranularity.DAILY,
            version="1.0.0",
            expression="SUM(payment.amount)",
            source_lineage=("payment", "journey-order"),
        )

        self.assertEqual(metric.metric_id, "metric-revenue")
        self.assertEqual(metric.status, MetricStatus.DRAFT)
        self.assertIsNone(metric.published_at)
        self.assertEqual(metric.category, MetricCategory.FINANCIAL)
        self.assertEqual(metric.granularity, MetricGranularity.DAILY)

    def test_publish_metric_requires_approvable_status(self) -> None:
        metric = define_metric(
            metric_id="metric-1",
            name="Test Metric",
            description="A test metric",
            owner="team-a",
            category=MetricCategory.OPERATIONAL,
            granularity=MetricGranularity.DAILY,
            version="1.0.0",
            expression="COUNT(orders)",
        )

        published = publish_metric_version(metric, NOW)
        self.assertEqual(published.status, MetricStatus.PUBLISHED)
        self.assertEqual(published.published_at, NOW)

        # Publishing a second time should fail
        with self.assertRaisesRegex(ReportingError, "cannot publish metric"):
            publish_metric_version(published, NOW)

    def test_supersede_and_deprecate_metric_lifecycle(self) -> None:
        metric = define_metric(
            metric_id="metric-2",
            name="Test Metric",
            description="desc",
            owner="team-a",
            category=MetricCategory.OPERATIONAL,
            granularity=MetricGranularity.DAILY,
            version="1.0.0",
            expression="COUNT(orders)",
        )
        published = publish_metric_version(metric, NOW)
        superseded = published.supersede(NOW + timedelta(hours=1))
        self.assertEqual(superseded.status, MetricStatus.SUPERSEDED)

        deprecated = superseded.deprecate(NOW + timedelta(hours=2))
        self.assertEqual(deprecated.status, MetricStatus.DEPRECATED)

        with self.assertRaisesRegex(ReportingError, "published or superseded"):
            metric.deprecate(NOW)

    def test_draft_metric_required_fields_are_validated(self) -> None:
        with self.assertRaisesRegex(ReportingError, "metric_id is required"):
            define_metric(
                metric_id="",
                name="Test",
                description="desc",
                owner="team-a",
                category=MetricCategory.OPERATIONAL,
                granularity=MetricGranularity.DAILY,
                version="1.0.0",
                expression="COUNT(orders)",
            )


class DashboardReadModelTest(unittest.TestCase):
    def test_create_dashboard_and_add_metric(self) -> None:
        dashboard = DashboardReadModel(
            dashboard_id="dash-revenue",
            name="Revenue Dashboard",
            description="Daily revenue metrics",
            source_events=("payment.PaymentCaptured", "journey-order.JourneyOrderConfirmed"),
        )
        self.assertEqual(dashboard.status, ReadModelStatus.BUILDING)
        self.assertIsNone(dashboard.current_snapshot)

        metric_ref = MetricRef(metric_id="metric-revenue", version="1.0.0")
        with_metric = dashboard.add_metric(metric_ref)
        self.assertEqual(len(with_metric.metrics), 1)

        with self.assertRaisesRegex(ReportingError, "already referenced"):
            with_metric.add_metric(metric_ref)

    def test_rebuild_dashboard_produces_snapshot(self) -> None:
        dashboard = DashboardReadModel(
            dashboard_id="dash-revenue",
            name="Revenue Dashboard",
            description="desc",
        ).add_metric(MetricRef(metric_id="metric-revenue", version="1.0.0"))

        rebuilt = project_event(
            dashboard,
            rebuild_id="rebuild-001",
            rebuilt_at=NOW,
            event_count=1500,
            digest="sha256-abc123",
        )
        self.assertEqual(rebuilt.status, ReadModelStatus.READY)
        self.assertIsNotNone(rebuilt.current_snapshot)
        assert rebuilt.current_snapshot is not None
        self.assertEqual(rebuilt.current_snapshot.rebuild_id, "rebuild-001")
        self.assertEqual(rebuilt.current_snapshot.event_count, 1500)
        self.assertEqual(rebuilt.current_snapshot.digest, "sha256-abc123")

    def test_cannot_rebuild_dashboard_without_metrics(self) -> None:
        dashboard = DashboardReadModel(
            dashboard_id="dash-empty",
            name="Empty Dashboard",
            description="desc",
        )
        with self.assertRaisesRegex(ReportingError, "no metrics"):
            project_event(
                dashboard,
                rebuild_id="rebuild-001",
                rebuilt_at=NOW,
                event_count=0,
                digest="empty",
            )

    def test_mark_stale(self) -> None:
        dashboard = DashboardReadModel(
            dashboard_id="dash-stale",
            name="Stale Dashboard",
            description="desc",
        )
        stale = dashboard.mark_stale()
        self.assertEqual(stale.status, ReadModelStatus.STALE)

    def test_mark_stale_fails_on_failed_dashboard(self) -> None:
        dashboard = DashboardReadModel(
            dashboard_id="dash-failed",
            name="Failed Dashboard",
            description="desc",
            status=ReadModelStatus.FAILED,
        )
        with self.assertRaisesRegex(ReportingError, "cannot mark a failed"):
            dashboard.mark_stale()


class FunnelViewTest(unittest.TestCase):
    def test_create_funnel_view(self) -> None:
        funnel = FunnelView(
            funnel_id="funnel-order",
            name="Order Funnel",
            dimension_filters=("channel", "route"),
        )
        self.assertEqual(funnel.funnel_id, "funnel-order")
        self.assertEqual(len(funnel.steps), 0)

    def test_rebuild_funnel_view(self) -> None:
        funnel = FunnelView(
            funnel_id="funnel-order",
            name="Order Funnel",
        )
        steps = (
            FunnelStepCount(FunnelStep.SEARCH, 10000),
            FunnelStepCount(FunnelStep.OFFER_QUOTED, 5000),
            FunnelStepCount(FunnelStep.ORDER_CREATED, 2500),
            FunnelStepCount(FunnelStep.PAYMENT_CAPTURED, 2000),
            FunnelStepCount(FunnelStep.ENTITLEMENT_ISSUED, 1800),
        )
        rebuilt = rebuild_read_model(funnel, NOW, steps)
        self.assertEqual(rebuilt.last_built_at, NOW)
        self.assertEqual(len(rebuilt.steps), 5)
        self.assertEqual(rebuilt.steps[0].count, 10000)

    def test_update_step(self) -> None:
        funnel = FunnelView(
            funnel_id="funnel-order",
            name="Order Funnel",
            steps=(
                FunnelStepCount(FunnelStep.SEARCH, 10000),
                FunnelStepCount(FunnelStep.ORDER_CREATED, 2000),
            ),
        )
        updated = funnel.update_step(FunnelStep.ORDER_CREATED, 2500)
        self.assertEqual(updated.steps[1].count, 2500)

    def test_duplicate_step_validation(self) -> None:
        with self.assertRaisesRegex(ReportingError, "duplicate funnel step"):
            FunnelView(
                funnel_id="funnel-bad",
                name="Bad Funnel",
                steps=(
                    FunnelStepCount(FunnelStep.SEARCH, 100),
                    FunnelStepCount(FunnelStep.SEARCH, 200),
                ),
            )

    def test_negative_step_count(self) -> None:
        with self.assertRaisesRegex(ReportingError, "cannot be negative"):
            FunnelStepCount(FunnelStep.SEARCH, -1)

    def test_required_fields(self) -> None:
        with self.assertRaisesRegex(ReportingError, "funnel_id is required"):
            FunnelView(funnel_id="", name="Test")
        with self.assertRaisesRegex(ReportingError, "funnel name is required"):
            FunnelView(funnel_id="f-1", name="")


class ConsumedEventLogTest(unittest.TestCase):
    def test_idempotent_recording(self) -> None:
        log = ConsumedEventLog(log_id="consumed-log-1")
        record = ConsumedEventRecord(
            event_id="evt-001",
            consumed_at=NOW,
            source="journey-order",
            event_type="JourneyOrderCreated",
        )
        log1 = log.record(record)
        self.assertEqual(len(log1.records), 1)
        self.assertTrue(log1.has_consumed("evt-001"))

        # Recording the same event again is idempotent
        log2 = log1.record(record)
        self.assertEqual(len(log2.records), 1)

    def test_has_consumed(self) -> None:
        log = ConsumedEventLog(log_id="consumed-log-2")
        self.assertFalse(log.has_consumed("evt-999"))
        record = ConsumedEventRecord(
            event_id="evt-999",
            consumed_at=NOW,
            source="payment",
            event_type="PaymentCaptured",
        )
        log = log.record(record)
        self.assertTrue(log.has_consumed("evt-999"))

    def test_requires_log_id(self) -> None:
        with self.assertRaisesRegex(ReportingError, "log id is required"):
            ConsumedEventLog(log_id="")


class FunnelStepEnumTest(unittest.TestCase):
    def test_funnel_step_order(self) -> None:
        steps = list(FunnelStep)
        expected = [
            FunnelStep.SEARCH,
            FunnelStep.OFFER_QUOTED,
            FunnelStep.ORDER_CREATED,
            FunnelStep.BOOKING_CONFIRMED,
            FunnelStep.PAYMENT_CAPTURED,
            FunnelStep.ENTITLEMENT_ISSUED,
            FunnelStep.FULFILLMENT_COMPLETED,
        ]
        self.assertEqual(steps, expected)


class MetricRefTest(unittest.TestCase):
    def test_required_fields(self) -> None:
        with self.assertRaisesRegex(ReportingError, "metric_id is required"):
            MetricRef(metric_id="", version="1.0.0")
        with self.assertRaisesRegex(ReportingError, "version is required"):
            MetricRef(metric_id="m-1", version="")


class ReadModelSnapshotTest(unittest.TestCase):
    def test_requires_rebuild_id(self) -> None:
        with self.assertRaisesRegex(ReportingError, "rebuild_id is required"):
            ReadModelSnapshot(
                rebuild_id="",
                rebuilt_at=NOW,
                metrics=(),
                event_count=0,
                digest="d",
            )


class MetricDefinitionDirectValidationTest(unittest.TestCase):
    def test_published_metric_needs_published_at(self) -> None:
        with self.assertRaisesRegex(ReportingError, "must record published_at"):
            MetricDefinition(
                metric_id="m-1",
                name="Test",
                description="desc",
                owner="o",
                category=MetricCategory.OPERATIONAL,
                granularity=MetricGranularity.DAILY,
                version="1.0.0",
                expression="COUNT(*)",
                status=MetricStatus.PUBLISHED,
            )


class DashboardReadModelRequiredFieldsTest(unittest.TestCase):
    def test_requires_dashboard_id(self) -> None:
        with self.assertRaisesRegex(ReportingError, "dashboard_id is required"):
            DashboardReadModel(dashboard_id="", name="Test", description="desc")

    def test_requires_name(self) -> None:
        with self.assertRaisesRegex(ReportingError, "dashboard name is required"):
            DashboardReadModel(dashboard_id="d-1", name="", description="desc")


class RealTimeMetricsEnrichmentTest(unittest.TestCase):
    def test_ten_orders_are_reflected_in_orders_per_second(self) -> None:
        aggregator = MetricAggregator(currency="USD")
        for index in range(10):
            aggregator.record(
                OperationalEvent(
                    event_id=f"order-{index}",
                    event_type="JourneyOrderCreated",
                    occurred_at=NOW + timedelta(seconds=index),
                    route_id="G123",
                    booking_latency_ms=100 + index,
                )
            )

        snapshot = aggregator.snapshot(NOW + timedelta(seconds=30))

        self.assertEqual(snapshot.orders_per_second, 10)
        self.assertEqual(snapshot.avg_booking_latency["p50"], 104.5)

    def test_revenue_by_route_returns_top_routes(self) -> None:
        aggregator = MetricAggregator(currency="USD")
        payments = [("A", "100.00"), ("B", "300.00"), ("A", "50.00")]
        for index, (route, amount) in enumerate(payments):
            aggregator.record(
                OperationalEvent(
                    event_id=f"payment-{index}",
                    event_type="PaymentCaptured",
                    occurred_at=NOW + timedelta(minutes=index),
                    route_id=route,
                    amount=Money(amount, "USD"),
                    distance_km=Decimal("100"),
                    ancillary_attached=index != 1,
                    insurance_attached=index == 0,
                )
            )

        report = aggregator.revenue_report("route", at=NOW + timedelta(hours=1))

        self.assertEqual([item.value for item in report.items], ["B", "A"])
        self.assertEqual(report.items[0].revenue, Money("300.00", "USD"))
        self.assertEqual(report.items[1].yield_per_km, Decimal("0.75"))

    def test_payment_failure_rate_detects_anomaly(self) -> None:
        aggregator = MetricAggregator(currency="USD")
        detector = AnomalyDetector()
        for index in range(8):
            aggregator.record(OperationalEvent(f"ok-{index}", "PaymentCaptured", NOW + timedelta(seconds=index), amount=Money("10.00", "USD")))
        for index in range(2):
            aggregator.record(OperationalEvent(f"fail-{index}", "PaymentFailed", NOW + timedelta(seconds=20 + index)))

        anomalies = detector.evaluate(aggregator, NOW + timedelta(minutes=1))

        self.assertEqual(anomalies[0].rule_id, "ERROR_RATE_SPIKE")
        self.assertEqual(anomalies[0].severity, AnomalySeverity.CRITICAL)

    def test_context_rollups_count_source_contexts_and_anomaly_signals(self) -> None:
        aggregator = MetricAggregator(currency="USD")
        aggregator.record(OperationalEvent("waitlist-1", "WaitlistQueued", NOW, source_context="waitlist"))
        aggregator.record(OperationalEvent("waitlist-2", "WaitlistExpired", NOW + timedelta(minutes=1), source_context="waitlist", anomaly_signal=True))

        snapshot = aggregator.snapshot(NOW + timedelta(minutes=2))
        rollups = {(item.source_context, item.event_type): item for item in snapshot.context_rollups}

        self.assertEqual(rollups[("waitlist", "WaitlistQueued")].count, 1)
        self.assertEqual(rollups[("waitlist", "WaitlistExpired")].anomaly_rate, 1.0)

    def test_context_count_report_sorts_by_count_then_stable_value(self) -> None:
        aggregator = MetricAggregator(currency="USD")
        aggregator.record(OperationalEvent("dispatch-1", "DispatchRequested", NOW, source_context="dispatch"))
        aggregator.record(OperationalEvent("waitlist-1", "WaitlistQueued", NOW, source_context="waitlist"))
        aggregator.record(OperationalEvent("waitlist-2", "WaitlistExpired", NOW + timedelta(minutes=1), source_context="waitlist"))
        aggregator.record(OperationalEvent("ancillary-1", "AncillaryQuoted", NOW, source_context="ancillary-service"))

        report = aggregator.context_count_report("source_context", at=NOW + timedelta(minutes=2))

        self.assertEqual([item.value for item in report.items], ["waitlist", "ancillary-service", "dispatch"])
        self.assertEqual([item.count for item in report.items], [2, 1, 1])
        self.assertEqual(report.total_count, 4)

    def test_context_count_report_supports_event_type_dimension(self) -> None:
        aggregator = MetricAggregator(currency="USD")
        aggregator.record(OperationalEvent("dispatch-1", "DispatchRequested", NOW, source_context="dispatch"))
        aggregator.record(OperationalEvent("dispatch-2", "DispatchRequested", NOW + timedelta(seconds=1), source_context="dispatch"))
        aggregator.record(OperationalEvent("dispatch-3", "DispatchFailed", NOW + timedelta(seconds=2), source_context="dispatch"))
        aggregator.record(OperationalEvent("waitlist-1", "WaitlistQueued", NOW + timedelta(seconds=3), source_context="waitlist"))

        report = aggregator.context_count_report("event_type", at=NOW + timedelta(minutes=2))

        self.assertEqual([item.value for item in report.items], ["DispatchRequested", "DispatchFailed", "WaitlistQueued"])
        self.assertEqual([item.count for item in report.items], [2, 1, 1])
        self.assertEqual(report.total_count, 4)

    def test_context_count_report_rejects_invalid_counts(self) -> None:
        with self.assertRaisesRegex(ReportingError, "count must be positive"):
            ContextCountItem("source_context", "waitlist", 0)

    def test_context_rollup_rejects_invalid_counts(self) -> None:
        with self.assertRaisesRegex(ReportingError, "count must be positive"):
            ContextEventRollup("waitlist", "WaitlistQueued", 0, NOW)
        with self.assertRaisesRegex(ReportingError, "anomaly_count"):
            ContextEventRollup("waitlist", "WaitlistQueued", 1, NOW, anomaly_count=2)


if __name__ == "__main__":
    unittest.main()

class ParseOccurredAtTest(unittest.TestCase):
    """`occurredAt` reaches reporting as either a string or a datetime.

    python-kit's EventEnvelope carries it as a datetime once deserialised, and
    _parse_occurred_at assumed a string: `datetime.replace("Z", "+00:00")` binds
    those to the `year` and `month` keyword parameters and raises
    `TypeError: 'str' object cannot be interpreted as an integer`. The function's
    own `except ValueError` did not catch a TypeError, so it escaped handle_event
    and every consumed event on every stream returned TRANSIENT_ERROR -- 13,511
    pending messages, a dashboard stuck in `building` since 2026-07-05, and an
    empty revenue view, all from one wrong annotation.
    """

    def test_accepts_an_aware_datetime(self) -> None:
        from reporting.application.service import _parse_occurred_at
        at = datetime(2026, 9, 8, 12, 0, tzinfo=UTC)
        self.assertEqual(_parse_occurred_at(at), at)

    def test_assumes_utc_for_a_naive_datetime(self) -> None:
        from reporting.application.service import _parse_occurred_at
        result = _parse_occurred_at(datetime(2026, 9, 8, 12, 0))
        self.assertEqual(result, datetime(2026, 9, 8, 12, 0, tzinfo=UTC))

    def test_accepts_a_z_suffixed_string(self) -> None:
        from reporting.application.service import _parse_occurred_at
        self.assertEqual(
            _parse_occurred_at("2026-09-08T12:00:00.000Z"),
            datetime(2026, 9, 8, 12, 0, tzinfo=UTC),
        )

    def test_falls_back_for_garbage_rather_than_raising(self) -> None:
        from reporting.application.service import _parse_occurred_at
        # Must not raise: an unparseable timestamp should not stall a stream.
        self.assertIsNotNone(_parse_occurred_at("not-a-timestamp"))
        self.assertIsNotNone(_parse_occurred_at(None))
        self.assertIsNotNone(_parse_occurred_at(12345))
