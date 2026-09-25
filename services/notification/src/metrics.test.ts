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

  it("reports queue depth and oldest age as gauges with only state and channel", async () => {
    const exporter = new InMemoryMetricExporter(AggregationTemporality.CUMULATIVE);
    const reader = new PeriodicExportingMetricReader({ exporter, exportIntervalMillis: 60_000 });
    const provider = new MeterProvider({ readers: [reader] });
    try {
      const recorder = new OpenTelemetryNotificationMetrics(provider.getMeter("notification-queue-test"));
      recorder.queueDepth([
        { state: "Ready", channel: "EMAIL", tasks: 2_287_687, oldestAgeSeconds: 19_865 },
        { state: "Claimed", channel: "EMAIL", tasks: 93, oldestAgeSeconds: 0 },
      ]);
      await reader.forceFlush();

      const collected = new Map<string, ResourceMetrics["scopeMetrics"][number]["metrics"][number]>();
      for (const resource of exporter.getMetrics()) {
        for (const scope of resource.scopeMetrics) {
          for (const metric of scope.metrics) collected.set(metric.descriptor.name, metric);
        }
      }

      const tasks = collected.get("train_ticket.notification.queue.tasks");
      const oldest = collected.get("train_ticket.notification.queue.oldest_age");
      assert.ok(tasks, "queue depth gauge must be exported");
      assert.ok(oldest, "oldest age gauge must be exported");
      assert.equal(oldest.descriptor.unit, "s");
      const ready = tasks.dataPoints.find(point => point.attributes.state === "Ready");
      assert.equal(ready?.value, 2_287_687);
      assert.deepEqual(Object.keys(ready!.attributes).sort(), ["channel", "state"]);
      assert.equal(oldest.dataPoints.find(point => point.attributes.state === "Ready")?.value, 19_865);
    } finally {
      await provider.shutdown();
    }
  });

});
