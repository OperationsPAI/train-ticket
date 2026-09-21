import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { metrics } from "@opentelemetry/api";
import { InMemoryMetricExporter, PeriodicExportingMetricReader, type MetricReader, type ResourceMetrics } from "@opentelemetry/sdk-metrics";
import { AggregationTemporality } from "@opentelemetry/sdk-metrics";
import type { Pool } from "pg";

import {
  metricExportIntervalMillis,
  metricExportTimeoutMillis,
  POOL_NAME,
  registerPoolMetrics,
} from "./metrics.js";
import { initOpenTelemetry } from "./observability.js";

/**
 * A pool reporting pg's own counters. registerPoolMetrics reads nothing else, so
 * no PostgreSQL is needed to prove the wiring.
 */
function fakePool(counts: Readonly<{ totalCount: number; idleCount: number; waitingCount: number; max: number }>): Pool {
  return {
    totalCount: counts.totalCount,
    idleCount: counts.idleCount,
    waitingCount: counts.waitingCount,
    options: { max: counts.max },
  } as unknown as Pool;
}

/**
 * The meter provider is process-global and installed once per interpreter, so
 * one reader serves the whole file. A provider per test would leave every later
 * reader unregistered and collecting nothing.
 */
const exporter = new InMemoryMetricExporter(AggregationTemporality.CUMULATIVE);
const reader: MetricReader = new PeriodicExportingMetricReader({
  exporter,
  // Long enough that only the explicit collect() below produces data, so a
  // timer cannot interleave an export between a registration and its assertion.
  exportIntervalMillis: 600_000,
});

async function collect(): Promise<Map<string, ResourceMetrics["scopeMetrics"][number]["metrics"][number]>> {
  await reader.forceFlush();
  const byName = new Map<string, ResourceMetrics["scopeMetrics"][number]["metrics"][number]>();
  for (const resourceMetrics of exporter.getMetrics()) {
    for (const scopeMetrics of resourceMetrics.scopeMetrics) {
      for (const metric of scopeMetrics.metrics) {
        byName.set(metric.descriptor.name, metric);
      }
    }
  }
  return byName;
}

describe("application metrics", () => {
  it("does not initialize without OTEL_METRICS_EXPORTER or OTEL_TRACES_EXPORTER", () => {
    const previousMetrics = process.env.OTEL_METRICS_EXPORTER;
    const previousTraces = process.env.OTEL_TRACES_EXPORTER;
    process.env.OTEL_METRICS_EXPORTER = "none";
    delete process.env.OTEL_TRACES_EXPORTER;
    try {
      assert.equal(initOpenTelemetry(), undefined);
    } finally {
      restore("OTEL_METRICS_EXPORTER", previousMetrics);
      restore("OTEL_TRACES_EXPORTER", previousTraces);
    }
  });

  it("falls back to the specification's export interval", () => {
    const previous = process.env.OTEL_METRIC_EXPORT_INTERVAL;
    try {
      delete process.env.OTEL_METRIC_EXPORT_INTERVAL;
      assert.equal(metricExportIntervalMillis(), 60_000);
      process.env.OTEL_METRIC_EXPORT_INTERVAL = "15000";
      assert.equal(metricExportIntervalMillis(), 15_000);
      // A value that is not a positive number governs only the export period, so
      // it falls back rather than failing startup.
      process.env.OTEL_METRIC_EXPORT_INTERVAL = "not-a-number";
      assert.equal(metricExportIntervalMillis(), 60_000);
      process.env.OTEL_METRIC_EXPORT_INTERVAL = "0";
      assert.equal(metricExportIntervalMillis(), 60_000);
    } finally {
      restore("OTEL_METRIC_EXPORT_INTERVAL", previous);
    }
  });

  it("keeps the export timeout inside the interval the reader accepts", () => {
    const previousInterval = process.env.OTEL_METRIC_EXPORT_INTERVAL;
    const previousTimeout = process.env.OTEL_METRIC_EXPORT_TIMEOUT;
    try {
      // The deployed pair. PeriodicExportingMetricReader refuses a timeout
      // above the interval, and the two SDK defaults are 30s and 60s, so a 15s
      // interval left to the default timeout cannot be constructed at all.
      process.env.OTEL_METRIC_EXPORT_INTERVAL = "15000";
      delete process.env.OTEL_METRIC_EXPORT_TIMEOUT;
      assert.equal(metricExportTimeoutMillis(), 15_000);

      // An interval above the default leaves the default in place.
      process.env.OTEL_METRIC_EXPORT_INTERVAL = "60000";
      assert.equal(metricExportTimeoutMillis(), 30_000);

      // A configured timeout is honoured up to the interval and no further.
      process.env.OTEL_METRIC_EXPORT_TIMEOUT = "5000";
      assert.equal(metricExportTimeoutMillis(), 5_000);
      process.env.OTEL_METRIC_EXPORT_TIMEOUT = "90000";
      assert.equal(metricExportTimeoutMillis(), 60_000);

      // As with the interval, a value that is not a positive number governs
      // only how long one export may take, so it falls back.
      process.env.OTEL_METRIC_EXPORT_TIMEOUT = "not-a-number";
      assert.equal(metricExportTimeoutMillis(), 30_000);
      process.env.OTEL_METRIC_EXPORT_TIMEOUT = "0";
      assert.equal(metricExportTimeoutMillis(), 30_000);
    } finally {
      restore("OTEL_METRIC_EXPORT_INTERVAL", previousInterval);
      restore("OTEL_METRIC_EXPORT_TIMEOUT", previousTimeout);
    }
  });

  it("builds a periodic reader the SDK accepts at the deployed interval", () => {
    // The regression this pins. PeriodicExportingMetricReader throws from its
    // own constructor when the timeout exceeds the interval, and the SDK
    // defaults are 30s and 60s, so the deployment's 15s interval left the pair
    // invalid and all seven TypeScript services crash-looped on
    // "exportIntervalMillis must be greater than or equal to
    // exportTimeoutMillis" before serving a request.
    //
    // The reader is constructed here rather than through initOpenTelemetry
    // because the SDK is a module-global installed once per interpreter, and a
    // second one started in this file would take the singleton away from the
    // collection test below. What crashed is this constructor, and this is the
    // call that reaches it.
    const previous = process.env.OTEL_METRIC_EXPORT_INTERVAL;
    try {
      process.env.OTEL_METRIC_EXPORT_INTERVAL = "15000";
      const periodic = new PeriodicExportingMetricReader({
        exporter: new InMemoryMetricExporter(AggregationTemporality.CUMULATIVE),
        exportIntervalMillis: metricExportIntervalMillis(),
        exportTimeoutMillis: metricExportTimeoutMillis(),
      });
      assert.notEqual(periodic, undefined);
      void periodic.shutdown();
    } finally {
      restore("OTEL_METRIC_EXPORT_INTERVAL", previous);
    }
  });

  it("publishes pool state and runtime metrics under their conventional names", async () => {
    process.env.OTEL_METRICS_EXPORTER = "otlp";
    process.env.OTEL_SERVICE_NAME = "ts-kit-test";
    const sdk = initOpenTelemetry({ serviceName: "ts-kit-test", metricReader: reader });
    assert.notEqual(sdk, undefined);

    // Ten connections, four of them free, so six are checked out; two callers
    // are queued on an exhausted pool, which is the signal a pool bound shows up
    // in first.
    registerPoolMetrics(
      metrics.getMeter("ts-kit-test"),
      fakePool({ totalCount: 10, idleCount: 4, waitingCount: 2, max: 10 }),
    );

    const collected = await collect();
    for (const name of [
      "db.client.connection.count",
      "db.client.connection.max",
      "db.client.connection.pending_requests",
    ]) {
      assert.ok(collected.has(name), `missing ${name}; collected ${[...collected.keys()].sort().join(", ")}`);
    }

    const byState = new Map(
      collected.get("db.client.connection.count")!.dataPoints.map((point) => [point.attributes["db.client.connection.state"], point.value]),
    );
    assert.deepEqual([...byState.entries()].sort(), [["idle", 4], ["used", 6]]);

    const pending = collected.get("db.client.connection.pending_requests")!.dataPoints;
    assert.deepEqual(pending.map((point) => point.value), [2]);
    assert.equal(pending[0]?.attributes["db.client.connection.pool.name"], POOL_NAME);

    assert.deepEqual(collected.get("db.client.connection.max")!.dataPoints.map((point) => point.value), [10]);

    // The Node ecosystem's own runtime names, from RuntimeNodeInstrumentation.
    for (const name of ["v8js.memory.heap.used", "nodejs.eventloop.utilization"]) {
      assert.ok(collected.has(name), `missing ${name}; collected ${[...collected.keys()].sort().join(", ")}`);
    }
  });
});

function restore(name: string, previous: string | undefined): void {
  if (previous === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = previous;
  }
}
