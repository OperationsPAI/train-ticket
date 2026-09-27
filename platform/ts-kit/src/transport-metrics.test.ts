import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { type Meter } from "@opentelemetry/api";
import {
  AggregationTemporality,
  InMemoryMetricExporter,
  MeterProvider,
  PeriodicExportingMetricReader,
  type MetricReader,
  type ResourceMetrics,
} from "@opentelemetry/sdk-metrics";

import { createEventEnvelope, HandlerError, RedisEventSubscriber, type EventEnvelope } from "./messaging.js";
import { initOpenTelemetry } from "./observability.js";
import { OutboxRelay } from "./storage.js";
import {
  createEventConsumerMetrics,
  createOutboxMetrics,
  EVENT_CONSUMER_DEAD_LETTERS_DESCRIPTION,
  EVENT_CONSUMER_DEAD_LETTERS_METRIC,
  EVENT_CONSUMER_DEAD_LETTERS_UNIT,
  EVENT_CONSUMER_FAILURES_DESCRIPTION,
  EVENT_CONSUMER_FAILURES_METRIC,
  EVENT_CONSUMER_FAILURES_UNIT,
  EVENT_CONSUMER_MESSAGES_DESCRIPTION,
  EVENT_CONSUMER_MESSAGES_METRIC,
  EVENT_CONSUMER_MESSAGES_UNIT,
  OUTBOX_FAILURES_DESCRIPTION,
  OUTBOX_FAILURES_METRIC,
  OUTBOX_FAILURES_UNIT,
  OUTBOX_OLDEST_UNPUBLISHED_AGE_DESCRIPTION,
  OUTBOX_OLDEST_UNPUBLISHED_AGE_METRIC,
  OUTBOX_OLDEST_UNPUBLISHED_AGE_UNIT,
  OUTBOX_PUBLISHED_DESCRIPTION,
  OUTBOX_PUBLISHED_METRIC,
  OUTBOX_PUBLISHED_UNIT,
  OUTBOX_UNPUBLISHED_SAMPLE_DESCRIPTION,
  OUTBOX_UNPUBLISHED_SAMPLE_METRIC,
  OUTBOX_UNPUBLISHED_SAMPLE_UNIT,
} from "./metrics.js";

type CollectedMetric = ResourceMetrics["scopeMetrics"][number]["metrics"][number];
type CollectedPoint = Readonly<{ attributes: Record<string, unknown>; value: number | Readonly<{ value: number }> }>;

// The global meter provider is process-wide, so one reader serves the file. The
// subscriber exercises the default wiring (eventConsumerMetricsFromEnv ->
// GlobalEventConsumerMetrics -> the process-global provider), which is only true
// once the SDK has installed that provider.
process.env.OTEL_METRICS_EXPORTER = "otlp";
process.env.OTEL_SERVICE_NAME = "ts-kit-transport-test";

const exporter = new InMemoryMetricExporter(AggregationTemporality.CUMULATIVE);
const reader: MetricReader = new PeriodicExportingMetricReader({ exporter, exportIntervalMillis: 600_000 });
initOpenTelemetry({ serviceName: "ts-kit-transport-test", metricReader: reader });

async function collect(): Promise<Map<string, CollectedMetric>> {
  return collectFrom(exporter, reader);
}

async function collectFrom(target: InMemoryMetricExporter, targetReader: MetricReader): Promise<Map<string, CollectedMetric>> {
  target.reset();
  await targetReader.forceFlush();
  const byName = new Map<string, CollectedMetric>();
  for (const resourceMetrics of target.getMetrics()) {
    for (const scopeMetrics of resourceMetrics.scopeMetrics) {
      for (const metric of scopeMetrics.metrics) {
        byName.set(metric.descriptor.name, metric);
      }
    }
  }
  return byName;
}

function sumByAttributes(metric: CollectedMetric | undefined, attributes: Record<string, string>): number {
  if (!metric) {
    return 0;
  }
  let total = 0;
  for (const point of metric.dataPoints as unknown as readonly CollectedPoint[]) {
    if (matches(point.attributes, attributes)) {
      total += numberValue(point.value);
    }
  }
  return total;
}

function singleValue(metric: CollectedMetric | undefined): number | undefined {
  const points = metric?.dataPoints as unknown as readonly CollectedPoint[] | undefined;
  return points && points.length > 0 ? numberValue(points[0].value) : undefined;
}

function numberValue(value: number | Readonly<{ value: number }>): number {
  return typeof value === "number" ? value : value.value;
}

function matches(actual: Record<string, unknown>, expected: Record<string, string>): boolean {
  return Object.entries(expected).every(([key, value]) => actual[key] === value);
}

async function quietly<T>(run: () => Promise<T>): Promise<T> {
  const warn = console.warn;
  const error = console.error;
  console.warn = () => undefined;
  console.error = () => undefined;
  try {
    return await run();
  } finally {
    console.warn = warn;
    console.error = error;
  }
}

function fakeRedis(): { xadds: unknown[][]; xacks: unknown[][]; redis: { xadd: (...args: unknown[]) => Promise<string>; xack: (...args: unknown[]) => Promise<number> } } {
  const xadds: unknown[][] = [];
  const xacks: unknown[][] = [];
  return {
    xadds,
    xacks,
    redis: {
      xadd: async (...args: unknown[]) => { xadds.push(args); return "1-1"; },
      xack: async (...args: unknown[]) => { xacks.push(args); return 1; },
    },
  };
}

function deliver(subscriber: RedisEventSubscriber, entry: [string, string[]], handler: (envelope: EventEnvelope) => unknown): Promise<void> {
  return (subscriber as unknown as {
    processEntry: (stream: string, group: string, consumerName: string, entry: [string, string[]], handler: (envelope: EventEnvelope) => unknown) => Promise<void>;
  }).processEntry("events:ts-kit-test", "ts-kit", "consumer-a", entry, handler);
}

describe("transport metric contract", () => {
  it("publishes the Java transport instruments with the same names, units and descriptions", async () => {
    const local = localReader();
    const consumer = createEventConsumerMetrics(local.meter);
    consumer.recordOutcome("success");
    consumer.recordFailure("handler_exception");
    consumer.recordDeadLetter("fatal_result");
    const outbox = createOutboxMetrics(local.meter);
    outbox.observeUnpublishedSample(3, 2_500);
    outbox.recordPublished(2);
    outbox.recordFailure("select");

    const collected = await collectFrom(local.exporter, local.reader);
    assert.ok(collected.size > 0);
    const expected: ReadonlyArray<readonly [string, string, string]> = [
      [EVENT_CONSUMER_MESSAGES_METRIC, EVENT_CONSUMER_MESSAGES_UNIT, EVENT_CONSUMER_MESSAGES_DESCRIPTION],
      [EVENT_CONSUMER_FAILURES_METRIC, EVENT_CONSUMER_FAILURES_UNIT, EVENT_CONSUMER_FAILURES_DESCRIPTION],
      [EVENT_CONSUMER_DEAD_LETTERS_METRIC, EVENT_CONSUMER_DEAD_LETTERS_UNIT, EVENT_CONSUMER_DEAD_LETTERS_DESCRIPTION],
      [OUTBOX_UNPUBLISHED_SAMPLE_METRIC, OUTBOX_UNPUBLISHED_SAMPLE_UNIT, OUTBOX_UNPUBLISHED_SAMPLE_DESCRIPTION],
      [OUTBOX_OLDEST_UNPUBLISHED_AGE_METRIC, OUTBOX_OLDEST_UNPUBLISHED_AGE_UNIT, OUTBOX_OLDEST_UNPUBLISHED_AGE_DESCRIPTION],
      [OUTBOX_PUBLISHED_METRIC, OUTBOX_PUBLISHED_UNIT, OUTBOX_PUBLISHED_DESCRIPTION],
      [OUTBOX_FAILURES_METRIC, OUTBOX_FAILURES_UNIT, OUTBOX_FAILURES_DESCRIPTION],
    ];
    for (const [name, unit, description] of expected) {
      const metric = collected.get(name);
      assert.ok(metric, `missing ${name}; collected ${[...collected.keys()].sort().join(", ")}`);
      assert.equal(metric.descriptor.unit, unit, `${name} unit`);
      assert.equal(metric.descriptor.description, description, `${name} description`);
    }

    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_MESSAGES_METRIC), { outcome: "success" }), 1);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_FAILURES_METRIC), { "failure.type": "handler_exception" }), 1);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_DEAD_LETTERS_METRIC), { reason: "fatal_result" }), 1);
    assert.equal(singleValue(collected.get(OUTBOX_UNPUBLISHED_SAMPLE_METRIC)), 3);
    assert.equal(singleValue(collected.get(OUTBOX_OLDEST_UNPUBLISHED_AGE_METRIC)), 2.5);
    assert.equal(sumByAttributes(collected.get(OUTBOX_PUBLISHED_METRIC), {}), 2);
    assert.equal(sumByAttributes(collected.get(OUTBOX_FAILURES_METRIC), { stage: "select" }), 1);
  });
});

describe("event-consumer transport metrics", () => {
  it("wires the default metrics through the process-global provider", async () => {
    const { redis } = fakeRedis();
    // No explicit metrics: the subscriber must resolve the process-global meter.
    const subscriber = new RedisEventSubscriber(redis as never);
    const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(createEventEnvelope({ eventType: "Delivered", producer: "ts-kit-test", payload: {} }))]];

    const before = sumByAttributes((await collect()).get(EVENT_CONSUMER_MESSAGES_METRIC), { outcome: "success" });
    await quietly(() => deliver(subscriber, entry, () => undefined));
    const after = sumByAttributes((await collect()).get(EVENT_CONSUMER_MESSAGES_METRIC), { outcome: "success" });

    assert.equal(after - before, 1);
  });

  it("records a successful delivery as one success outcome", async () => {
    const local = localReader();
    const { redis } = fakeRedis();
    const subscriber = new RedisEventSubscriber(redis as never, undefined, { eventConsumerMetrics: createEventConsumerMetrics(local.meter) });
    const envelope = createEventEnvelope({ eventType: "Delivered", producer: "ts-kit-test", payload: {} });
    const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(envelope)]];

    await quietly(() => deliver(subscriber, entry, () => undefined));

    const collected = await collectFrom(local.exporter, local.reader);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_MESSAGES_METRIC), { outcome: "success" }), 1);
  });

  it("records a redelivered event id as a duplicate instead of a second success", async () => {
    const local = localReader();
    const { redis, xacks } = fakeRedis();
    const subscriber = new RedisEventSubscriber(redis as never, undefined, { eventConsumerMetrics: createEventConsumerMetrics(local.meter) });
    const envelope = createEventEnvelope({ eventType: "Delivered", producer: "ts-kit-test", payload: {} });
    const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(envelope)]];

    await quietly(async () => {
      await deliver(subscriber, entry, () => undefined);
      await deliver(subscriber, entry, () => { throw new Error("handler must be skipped"); });
    });

    const collected = await collectFrom(local.exporter, local.reader);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_MESSAGES_METRIC), { outcome: "duplicate" }), 1);
    assert.equal(xacks.length, 2);
  });

  it("records a transient result below the attempt limit as transient_result and retry", async () => {
    const local = localReader();
    const { redis, xadds, xacks } = fakeRedis();
    const subscriber = new RedisEventSubscriber(redis as never, undefined, { eventConsumerMetrics: createEventConsumerMetrics(local.meter) });
    const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(createEventEnvelope({ eventType: "Flaky", producer: "ts-kit-test", payload: {} }))]];

    await quietly(async () => {
      for (let attempt = 0; attempt < 4; attempt += 1) {
        await deliver(subscriber, entry, () => ({ ok: false, kind: "transient" }));
      }
    });

    const collected = await collectFrom(local.exporter, local.reader);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_FAILURES_METRIC), { "failure.type": "transient_result" }), 4);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_MESSAGES_METRIC), { outcome: "retry" }), 4);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_DEAD_LETTERS_METRIC), { reason: "transient_result" }), 0);
    assert.deepEqual([xadds.length, xacks.length], [0, 0]);
  });

  it("records a transient result at the attempt limit as transient_result and dead_letter", async () => {
    const local = localReader();
    const { redis, xadds, xacks } = fakeRedis();
    const subscriber = new RedisEventSubscriber(redis as never, undefined, { eventConsumerMetrics: createEventConsumerMetrics(local.meter) });
    const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(createEventEnvelope({ eventType: "Poisoned", producer: "ts-kit-test", payload: {} }))]];

    await quietly(async () => {
      for (let attempt = 0; attempt < 5; attempt += 1) {
        await deliver(subscriber, entry, () => ({ ok: false, kind: "transient" }));
      }
    });

    const collected = await collectFrom(local.exporter, local.reader);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_FAILURES_METRIC), { "failure.type": "transient_result" }), 5);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_MESSAGES_METRIC), { outcome: "retry" }), 4);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_MESSAGES_METRIC), { outcome: "dead_letter" }), 1);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_DEAD_LETTERS_METRIC), { reason: "transient_result" }), 1);
    assert.equal(xadds.length, 1);
    assert.equal(xacks.length, 1);
  });

  it("classifies a thrown handler error as handler_exception, not a transient result", async () => {
    const local = localReader();
    const { redis } = fakeRedis();
    const subscriber = new RedisEventSubscriber(redis as never, undefined, { eventConsumerMetrics: createEventConsumerMetrics(local.meter) });
    const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(createEventEnvelope({ eventType: "Flaky", producer: "ts-kit-test", payload: {} }))]];

    await quietly(() => deliver(subscriber, entry, () => { throw new HandlerError("transient", "redis unavailable"); }));

    const collected = await collectFrom(local.exporter, local.reader);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_FAILURES_METRIC), { "failure.type": "handler_exception" }), 1);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_FAILURES_METRIC), { "failure.type": "transient_result" }), 0);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_MESSAGES_METRIC), { outcome: "retry" }) >= 1, true);
  });

  it("records a fatal handler result as fatal_result and a dead letter", async () => {
    const local = localReader();
    const { redis, xadds } = fakeRedis();
    const subscriber = new RedisEventSubscriber(redis as never, undefined, { eventConsumerMetrics: createEventConsumerMetrics(local.meter) });
    const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(createEventEnvelope({ eventType: "Poisoned", producer: "ts-kit-test", payload: {} }))]];

    await quietly(() => deliver(subscriber, entry, () => ({ ok: false, kind: "fatal" })));

    const collected = await collectFrom(local.exporter, local.reader);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_FAILURES_METRIC), { "failure.type": "fatal_result" }), 1);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_DEAD_LETTERS_METRIC), { reason: "fatal_result" }), 1);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_MESSAGES_METRIC), { outcome: "dead_letter" }), 1);
    assert.equal(xadds.length, 1);
  });

  it("records a missing envelope as missing_envelope and a dead letter", async () => {
    const local = localReader();
    const { redis, xadds, xacks } = fakeRedis();
    const subscriber = new RedisEventSubscriber(redis as never, undefined, { eventConsumerMetrics: createEventConsumerMetrics(local.meter) });
    const entry: [string, string[]] = ["1-0", ["unexpected", "field"]];

    await quietly(() => deliver(subscriber, entry, () => undefined));

    const collected = await collectFrom(local.exporter, local.reader);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_FAILURES_METRIC), { "failure.type": "missing_envelope" }), 1);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_DEAD_LETTERS_METRIC), { reason: "missing_envelope" }), 1);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_MESSAGES_METRIC), { outcome: "dead_letter" }), 1);
    assert.equal(xadds.length, 1);
    assert.equal(xacks.length, 1);
  });

  it("records a failed dead-letter hand-off as dlq_write and leaves the source pending", async () => {
    const local = localReader();
    const { redis, xadds, xacks } = fakeRedis();
    const subscriber = new RedisEventSubscriber(redis as never, undefined, {
      deadLetterEvidenceSink: { recordDeadLetter: async () => { throw new Error("postgres unavailable"); } },
      eventConsumerMetrics: createEventConsumerMetrics(local.meter),
    });
    const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(createEventEnvelope({ eventType: "Poisoned", producer: "ts-kit-test", payload: {} }))]];

    await quietly(async () => {
      await assert.rejects(deliver(subscriber, entry, () => ({ ok: false, kind: "fatal" })), /postgres unavailable/u);
    });

    const collected = await collectFrom(local.exporter, local.reader);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_FAILURES_METRIC), { "failure.type": "dlq_write" }), 1);
    assert.equal(sumByAttributes(collected.get(EVENT_CONSUMER_DEAD_LETTERS_METRIC), { reason: "fatal_result" }), 0);
    assert.deepEqual([xadds.length, xacks.length], [0, 0]);
  });
});

describe("outbox relay transport metrics", () => {
  it("samples the bounded head-of-line and records the published rows", async () => {
    const local = localReader();
    const now = Date.now();
    const rows = [
      { seq: 1, stream: "events:order", envelope: { eventId: "evt-1" }, created_at: new Date(now - 5_000) },
      { seq: 2, stream: "events:order", envelope: { eventId: "evt-2" }, created_at: new Date(now - 1_000) },
    ];
    const relay = new OutboxRelay(fakeRelayPool(rows) as never, fakePipelineRedis() as never, {
      batchSize: 10,
      outboxMetrics: createOutboxMetrics(local.meter),
      trackLiveness: false,
    });

    assert.equal(await relay.runOnce(), 2);

    const collected = await collectFrom(local.exporter, local.reader);
    const sample = collected.get(OUTBOX_UNPUBLISHED_SAMPLE_METRIC);
    assert.ok(sample);
    assert.equal(singleValue(sample), 2);
    const age = singleValue(collected.get(OUTBOX_OLDEST_UNPUBLISHED_AGE_METRIC));
    assert.ok(age !== undefined && Math.abs(age - 5) < 1, `expected ~5s, got ${age}`);
    assert.equal(sumByAttributes(collected.get(OUTBOX_PUBLISHED_METRIC), {}), 2);
  });

  it("records a failed select under its own stage", async () => {
    const local = localReader();
    const relay = new OutboxRelay(fakeRelayPool([], { failSelect: true }) as never, fakePipelineRedis() as never, {
      batchSize: 10,
      outboxMetrics: createOutboxMetrics(local.meter),
      trackLiveness: false,
    });

    await assert.rejects(relay.runOnce(), /select exploded/u);

    const collected = await collectFrom(local.exporter, local.reader);
    assert.equal(sumByAttributes(collected.get(OUTBOX_FAILURES_METRIC), { stage: "select" }), 1);
  });
});

/**
 * A private provider per test, so an observable gauge's last value is only ever
 * set by the relay under test rather than shared with another provider's scope.
 */
function localReader(): { meter: Meter; reader: MetricReader; exporter: InMemoryMetricExporter } {
  const localExporter = new InMemoryMetricExporter(AggregationTemporality.CUMULATIVE);
  const localMetricReader = new PeriodicExportingMetricReader({ exporter: localExporter, exportIntervalMillis: 600_000 });
  const provider = new MeterProvider({ readers: [localMetricReader] });
  return { meter: provider.getMeter("ts-kit-transport-outbox-test"), reader: localMetricReader, exporter: localExporter };
}

function fakeRelayPool(
  rows: ReadonlyArray<Readonly<{ seq: number; stream: string; envelope: unknown; created_at: Date }>>,
  options: Readonly<{ failSelect?: boolean }> = {},
): { connect: () => Promise<unknown> } {
  return {
    async connect() {
      return {
        query: async (sql: string) => {
          if (sql === "BEGIN" || sql === "COMMIT" || sql === "ROLLBACK") {
            return { rows: [], rowCount: 0 };
          }
          if (sql.includes("SELECT seq, stream, envelope")) {
            if (options.failSelect) {
              throw new Error("select exploded");
            }
            return { rows, rowCount: rows.length };
          }
          if (sql.includes("UPDATE outbox SET published_at")) {
            return { rows: [], rowCount: rows.length };
          }
          throw new Error(`Unexpected query ${sql}`);
        },
        release: () => undefined,
      };
    },
  };
}

function fakePipelineRedis(): { pipeline: () => unknown } {
  return {
    pipeline() {
      const redisPipeline = {
        xadd() {
          return redisPipeline;
        },
        async exec() {
          return [[null, "1-0"]];
        },
      };
      return redisPipeline;
    },
  };
}
