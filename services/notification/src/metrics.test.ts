import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { AggregationTemporality, InMemoryMetricExporter, MeterProvider, PeriodicExportingMetricReader, type ResourceMetrics } from "@opentelemetry/sdk-metrics";

import { deliveryReasonCode, OpenTelemetryNotificationMetrics } from "./metrics.js";

describe("notification metrics", () => {
  it("maps provider details to a bounded reason vocabulary", () => {
    assert.equal(deliveryReasonCode("Delivered"), "none");
    assert.equal(deliveryReasonCode("Rejected", "550 mailbox unavailable"), "provider_rejected");
    assert.equal(deliveryReasonCode("Timeout", "ECONNRESET"), "provider_timeout");
    assert.equal(deliveryReasonCode("Unknown", "SEND_LEASE_EXPIRED"), "send_lease_expired");
    assert.equal(deliveryReasonCode("Unknown", "dynamic-provider-value"), "other");
  });

  it("does not export task, recipient, message, or provider identifiers", async () => {
    const exporter = new InMemoryMetricExporter(AggregationTemporality.CUMULATIVE);
    const reader = new PeriodicExportingMetricReader({ exporter, exportIntervalMillis: 60_000 });
    const provider = new MeterProvider({ readers: [reader] });
    try {
      const recorder = new OpenTelemetryNotificationMetrics(provider.getMeter("notification-test"));
      recorder.deliveryResult("EMAIL", "failed", "provider_rejected");
      recorder.deliveryResult("EMAIL", "failed", "provider_rejected");
      recorder.retry("EMAIL", "scheduled", "provider_timeout");
      recorder.policyAction("expire", "EMAIL", "cancelled", "ttl_order_state");
      await reader.forceFlush();

      const collected = new Map<string, ResourceMetrics["scopeMetrics"][number]["metrics"][number]>();
      for (const resource of exporter.getMetrics()) {
        for (const scope of resource.scopeMetrics) {
          for (const metric of scope.metrics) collected.set(metric.descriptor.name, metric);
        }
      }
      const attributes = [...collected.values()]
        .flatMap(metric => metric.dataPoints.map(point => point.attributes));
      assert.ok(attributes.length >= 3);
      for (const labels of attributes) {
        assert.deepEqual(Object.keys(labels).sort(), Object.keys(labels).filter(key => ["action", "channel", "outcome", "reason_code"].includes(key)).sort());
      }
      const delivery = collected.get("train_ticket.notification.delivery_results");
      assert.equal(delivery?.dataPoints[0]?.value, 2);
    } finally {
      await provider.shutdown();
    }
  });
});
