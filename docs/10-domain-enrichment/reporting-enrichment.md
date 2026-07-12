# Reporting Enrichment — Real-Time Metrics & Analytics

## Context

**Service**: reporting (Java, `services/reporting/`)
**Current state**: 539-line domain, basic event consumption. No aggregation, no dashboard, no anomaly detection.

## Requirements

### R1: Real-Time Operational Metrics

Sliding window counters: orders_per_second (60s), revenue_per_hour (1h rolling), fill_rate_by_route (confirmed/capacity per route per day), avg_booking_latency (p50/p95/p99), refund_rate (24h rolling), scalper_block_rate (1h rolling). Per-route: route_revenue, route_demand (searches/bookings), seat_utilization by class.

Domain: MetricAggregator, MetricSnapshot, RouteMetrics.

### R2: Revenue Analytics

Breakdowns by route (top 20), seat class, channel (web/app/corporate), time period, passenger type. Derived: yield_per_km, ancillary_attach_rate, insurance_attach_rate.

Domain: RevenueReport, RevenueItem. Materialized PG views for performance.

### R3: Anomaly Detection

Rules: REVENUE_DROP (<50% vs same-hour yesterday), ORDER_SPIKE (>3x rolling avg), ERROR_RATE_SPIKE (payment fail >10% in 5min), CAPACITY_EXHAUSTION (>5 routes at 100%), REFUND_SURGE (>20% in 1h). Actions: publish AnomalyDetected event, log alert, urgent notification for revenue/error spikes.

Domain: AnomalyRule, AnomalyDetector. Events: AnomalyDetected with ruleId, currentValue, threshold, severity.

### R4: Dashboard API

GET /api/v1/metrics/operational (current ops), /routes (per-route), /revenue (breakdown), /anomalies (active alerts), /trends (time-series).

## Events Consumed

ALL service events (journey-order, payment, post-sales, capacity, fare-pricing, etc.)

## Events Produced

AnomalyDetected (consumers: admin-audit, notification). DailyReportGenerated (consumers: finance-settlement).

## Test Criteria

1. 10 orders captured -> orders_per_second metric reflects count
2. Revenue by route returns correct top routes
3. Payment failure rate >10% -> anomaly detected
4. Dashboard API returns current metrics

## Files to Modify

- src/main/java/.../reporting/domain/ -- aggregators, anomaly rules
- src/main/java/.../reporting/application/
- src/main/java/.../reporting/adapters/api/
- migrations/
