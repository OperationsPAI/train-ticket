import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { context, trace } from "@opentelemetry/api";
import { InMemorySpanExporter } from "@opentelemetry/sdk-trace-base";

import { configuredStreamMaxLen, createEventEnvelope, DEFAULT_STREAM_MAXLEN, HandlerError, InMemoryEventSubscriber, RedisEventPublisher, RedisEventSubscriber, STREAM_MAXLEN_ENV, streamMaxLen, type EventEnvelope } from "./messaging.js";
import { initOpenTelemetry } from "./observability.js";
import { isPrefixedUuidV7, newCommandId, newCorrelationId, newEventId } from "./ids.js";

const sharedTraceExporter = new InMemorySpanExporter();

describe("EventEnvelope factory", () => {
  it("generates v7-prefixed envelope IDs when callers omit them", () => {
    const envelope = createEventEnvelope({
      eventType: "ExampleCreated",
      producer: "ts-kit-test",
      payload: {},
    });

    assert.equal(isPrefixedUuidV7(envelope.eventId, ["evt"]), true);
    assert.equal(isPrefixedUuidV7(envelope.correlationId, ["corr"]), true);
    assert.equal(isPrefixedUuidV7(envelope.causationId ?? "", ["cmd"]), true);
  });

  it("accepts caller-supplied v7-prefixed envelope IDs", () => {
    const eventId = newEventId();
    const correlationId = newCorrelationId();
    const causationId = newCommandId();

    const envelope = createEventEnvelope({
      eventId,
      correlationId,
      causationId,
      eventType: "ExampleCreated",
      producer: "ts-kit-test",
      payload: {},
    });

    assert.equal(envelope.eventId, eventId);
    assert.equal(envelope.correlationId, correlationId);
    assert.equal(envelope.causationId, causationId);
  });

  it("rejects v4 and garbage caller-supplied IDs", () => {
    assert.throws(
      () => createEventEnvelope({
        eventId: "evt-550e8400-e29b-41d4-a716-446655440000",
        eventType: "ExampleCreated",
        producer: "ts-kit-test",
        payload: {},
      }),
      /UUID v7/,
    );
    assert.throws(
      () => createEventEnvelope({
        correlationId: "corr-not-a-uuid",
        eventType: "ExampleCreated",
        producer: "ts-kit-test",
        payload: {},
      }),
      /UUID v7/,
    );
    assert.throws(
      () => createEventEnvelope({
        causationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
        eventType: "ExampleCreated",
        producer: "ts-kit-test",
        payload: {},
      }),
      /cmd- or evt-|cmd-/,
    );
  });


});


describe("RedisEventSubscriber DLQ observability", () => {

  it("uses pending delivery count as DLQ attempts for claimed poison entries", async () => {
    const xadds: unknown[][] = [];
    const redis = {
      xadd: async (...args: unknown[]) => { xadds.push(args); return "2-1"; },
      xack: async () => 1,
      xautoclaim: async () => ["0-0", [["2-0", ["envelope", JSON.stringify(createEventEnvelope({ eventType: "Poisoned", producer: "ts-kit-test", payload: {} }))]]]],
      xpending: async () => [["2-0", "consumer-old", 10_000, 3]],
    };
    const subscriber = new RedisEventSubscriber(redis as never);
    const originalWarn = console.warn;
    console.warn = () => undefined;
    try {
      await (subscriber as unknown as { claimAndProcess: (stream: string, group: string, consumerName: string, handler: () => unknown) => Promise<void> })
        .claimAndProcess("events:ts-kit-test", "ts-kit", "consumer-a", () => { throw new HandlerError("fatal", "bad payload"); });
    } finally {
      console.warn = originalWarn;
    }

    const args = xadds[0];
    assert.equal(args[13], "attempts");
    assert.equal(args[14], "3");
  });

  it("adds attribution metadata and warns when dead-lettering fatal handler errors", async () => {
    const xadds: unknown[][] = [];
    const xacks: unknown[][] = [];
    const redis = {
      xadd: async (...args: unknown[]) => { xadds.push(args); return "1-1"; },
      xack: async (...args: unknown[]) => { xacks.push(args); return 1; },
    };
    const subscriber = new RedisEventSubscriber(redis as never);
    const envelope = createEventEnvelope({ eventType: "Poisoned", producer: "ts-kit-test", payload: {} });
    const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(envelope)]];
    const warnings: unknown[] = [];
    const originalWarn = console.warn;
    console.warn = (value?: unknown) => { warnings.push(value); };
    try {
      await (subscriber as unknown as { processEntry: (stream: string, group: string, consumerName: string, entry: [string, string[]], handler: () => unknown) => Promise<void> })
        .processEntry("events:ts-kit-test", "ts-kit", "consumer-a", entry, () => { throw new HandlerError("fatal", "bad payload"); });
    } finally {
      console.warn = originalWarn;
    }

    const args = xadds[0];
    assert.equal(args[0], "events:ts-kit-test:dlq");
    assert.equal(args[5], "envelope");
    assert.equal(args[7], "consumerGroup");
    assert.equal(args[8], "ts-kit");
    assert.equal(args[9], "consumerName");
    assert.equal(args[10], "consumer-a");
    assert.equal(args[11], "failureReason");
    assert.equal(args[12], "HandlerError: bad payload");
    assert.equal(args[13], "attempts");
    assert.equal(args[14], "1");
    assert.equal(args[15], "deadLetteredAt");
    assert.equal(typeof args[16], "string");
    assert.equal(xacks.length, 1);
    assert.deepEqual(warnings[0], {
      service: "ts-kit",
      stream: "events:ts-kit-test",
      eventId: envelope.eventId,
      deliveries: 1,
      consumerGroup: "ts-kit",
      failureReason: "HandlerError: bad payload",
      attempts: 1,
      deadLetteredAt: args[16],
      message: "moving message to DLQ",
    });
  });
});

describe("RedisEventSubscriber retry and ack-skip observability", () => {
  it("warns when transient handler errors stay pending for retry", async () => {
    const redis = {
      xack: async () => { throw new Error("transient retry should not ack"); },
      xadd: async () => { throw new Error("transient retry should not DLQ"); },
    };
    const subscriber = new RedisEventSubscriber(redis as never);
    const envelope = createEventEnvelope({ eventType: "Retryable", producer: "ts-kit-test", payload: {} });
    const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(envelope)]];
    const warnings: unknown[] = [];
    const originalWarn = console.warn;
    console.warn = (value?: unknown) => { warnings.push(value); };
    try {
      await (subscriber as unknown as { processEntry: (stream: string, group: string, consumerName: string, entry: [string, string[]], handler: () => unknown) => Promise<void> })
        .processEntry("events:ts-kit-test", "ts-kit", "consumer-a", entry, () => { throw new HandlerError("transient", "redis unavailable"); });
    } finally {
      console.warn = originalWarn;
    }

    assert.equal(warnings.length, 1);
    assert.equal((warnings[0] as { message?: string }).message, "handler transient failure; message stays pending for retry");
    assert.equal((warnings[0] as { eventId?: string }).eventId, envelope.eventId);
  });

  it("warns when duplicate events are acked without invoking the handler", async () => {
    const xacks: unknown[][] = [];
    const redis = {
      xack: async (...args: unknown[]) => { xacks.push(args); return 1; },
      xadd: async () => { throw new Error("duplicate ack skip should not DLQ"); },
    };
    const subscriber = new RedisEventSubscriber(redis as never);
    const envelope = createEventEnvelope({ eventType: "Duplicate", producer: "ts-kit-test", payload: {} });
    const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(envelope)]];
    const warnings: unknown[] = [];
    const originalWarn = console.warn;
    console.warn = (value?: unknown) => { warnings.push(value); };
    try {
      await (subscriber as unknown as { processEntry: (stream: string, group: string, consumerName: string, entry: [string, string[]], handler: () => unknown) => Promise<void> })
        .processEntry("events:ts-kit-test", "ts-kit", "consumer-a", entry, () => undefined);
      await (subscriber as unknown as { processEntry: (stream: string, group: string, consumerName: string, entry: [string, string[]], handler: () => unknown) => Promise<void> })
        .processEntry("events:ts-kit-test", "ts-kit", "consumer-a", entry, () => { throw new Error("handler must be skipped"); });
    } finally {
      console.warn = originalWarn;
    }

    assert.equal(xacks.length, 2);
    assert.equal(warnings.length, 1);
    assert.equal((warnings[0] as { message?: string }).message, "duplicate event already processed; acking without handler");
    assert.equal((warnings[0] as { eventId?: string }).eventId, envelope.eventId);
  });
});


async function waitForSpan(exporter: InMemorySpanExporter, name: string) {
  const deadline = Date.now() + 2_000;
  while (Date.now() < deadline) {
    const span = exporter.getFinishedSpans().find((candidate) => candidate.name === name);
    if (span) {
      return span;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  return exporter.getFinishedSpans().find((candidate) => candidate.name === name);
}

describe("EventEnvelope trace context", () => {
  it("omits trace context when OpenTelemetry tracing is disabled", () => {
    const previous = process.env.OTEL_TRACES_EXPORTER;
    delete process.env.OTEL_TRACES_EXPORTER;
    try {
      const envelope = createEventEnvelope({
        eventId: newEventId(),
        correlationId: newCorrelationId(),
        causationId: newCommandId(),
        eventType: "ExampleCreated",
        producer: "ts-kit-test",
        occurredAt: "2026-07-08T00:00:00.000Z",
        payload: {},
      });
      assert.deepEqual(JSON.parse(JSON.stringify(envelope)), envelope);
      assert.equal("traceparent" in envelope, false);
      assert.equal("tracestate" in envelope, false);
    } finally {
      if (previous === undefined) {
        delete process.env.OTEL_TRACES_EXPORTER;
      } else {
        process.env.OTEL_TRACES_EXPORTER = previous;
      }
    }
  });

  it("injects trace context and uses it as the consumer span remote parent", async () => {
    const previousExporter = process.env.OTEL_TRACES_EXPORTER;
    const previousService = process.env.OTEL_SERVICE_NAME;
    process.env.OTEL_TRACES_EXPORTER = "otlp";
    process.env.OTEL_SERVICE_NAME = "ts-kit-trace-test";
    const exporter = sharedTraceExporter;
    exporter.reset();
    const sdk = initOpenTelemetry({ serviceName: "ts-kit-trace-test", spanExporter: exporter });
    try {
      let envelope: EventEnvelope | undefined;
      let producerTraceId = "";
      let producerSpanId = "";
      const producer = trace.getTracer("ts-kit-trace-test").startSpan("producer");
      await context.with(trace.setSpan(context.active(), producer), async () => {
        envelope = createEventEnvelope({ eventType: "ExampleCreated", producer: "ts-kit-test", payload: {} });
        producerTraceId = producer.spanContext().traceId;
        producerSpanId = producer.spanContext().spanId;
      });
      producer.end();
      assert.ok(envelope?.traceparent);

      const subscriber = new InMemoryEventSubscriber();
      await subscriber.subscribe(["events:ts-kit-test"], "ts-kit", "consumer-a", () => undefined);
      assert.equal(await subscriber.simulateEvent(envelope), "ack");
      await (sdk as unknown as { forceFlush?: () => Promise<void> } | undefined)?.forceFlush?.();
      const consumer = await waitForSpan(exporter, "in-memory process ExampleCreated");
      assert.ok(consumer);
      assert.equal(consumer.spanContext().traceId, producerTraceId);
      assert.equal((consumer as unknown as { parentSpanId?: string }).parentSpanId, producerSpanId);
    } finally {
      if (previousExporter === undefined) {
        delete process.env.OTEL_TRACES_EXPORTER;
      } else {
        process.env.OTEL_TRACES_EXPORTER = previousExporter;
      }
      if (previousService === undefined) {
        delete process.env.OTEL_SERVICE_NAME;
      } else {
        process.env.OTEL_SERVICE_NAME = previousService;
      }
    }
  });

  it("deserializes unknown fields and ignores malformed traceparent for parent selection", async () => {
    const previousExporter = process.env.OTEL_TRACES_EXPORTER;
    process.env.OTEL_TRACES_EXPORTER = "otlp";
    const exporter = sharedTraceExporter;
    exporter.reset();
    const sdk = initOpenTelemetry({ serviceName: "ts-kit-trace-test", spanExporter: exporter });
    const xacks: unknown[][] = [];
    const redis = {
      xack: async (...args: unknown[]) => { xacks.push(args); return 1; },
      xadd: async () => { throw new Error("should not DLQ"); },
    };
    const subscriber = new RedisEventSubscriber(redis as never);
    const raw = {
      ...createEventEnvelope({ eventType: "ExampleCreated", producer: "ts-kit-test", payload: {} }),
      traceparent: "malformed",
      futureField: "ignored",
    };
    try {
      await (subscriber as unknown as { processEntry: (stream: string, group: string, consumerName: string, entry: [string, string[]], handler: () => unknown) => Promise<void> })
        .processEntry("events:ts-kit-test", "ts-kit", "consumer-a", ["1-0", ["envelope", JSON.stringify(raw)]], () => undefined);
      await (sdk as unknown as { forceFlush?: () => Promise<void> } | undefined)?.forceFlush?.();
      const consumer = await waitForSpan(exporter, "ts-kit process ExampleCreated");
      assert.ok(consumer);
      assert.equal((consumer as unknown as { parentSpanId?: string }).parentSpanId, undefined);
      assert.equal(xacks.length, 1);
    } finally {
      if (previousExporter === undefined) {
        delete process.env.OTEL_TRACES_EXPORTER;
      } else {
        process.env.OTEL_TRACES_EXPORTER = previousExporter;
      }
    }
  });
});
describe("stream MAXLEN cap", () => {
  it("publishes with approximate MAXLEN at the configured cap", async () => {
    const xadds: unknown[][] = [];
    const redis = {
      xadd: async (...args: unknown[]) => { xadds.push(args); return "1-1"; },
    };
    const publisher = new RedisEventPublisher(redis as never);

    await publisher.publish(createEventEnvelope({ eventType: "Capped", producer: "ts-kit-test", payload: {} }));

    // Assert on the real command arguments rather than trusting the code path.
    assert.deepEqual(xadds[0].slice(0, 5), ["events:ts-kit-test", "MAXLEN", "~", configuredStreamMaxLen(), "*"]);
  });

  it("caps DLQ writes with approximate MAXLEN at the configured cap", async () => {
    const xadds: unknown[][] = [];
    const redis = {
      xadd: async (...args: unknown[]) => { xadds.push(args); return "1-1"; },
      xack: async () => 1,
    };
    const subscriber = new RedisEventSubscriber(redis as never);
    const envelope = createEventEnvelope({ eventType: "Poisoned", producer: "ts-kit-test", payload: {} });
    const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(envelope)]];
    const originalWarn = console.warn;
    console.warn = () => undefined;
    try {
      await (subscriber as unknown as { processEntry: (stream: string, group: string, consumerName: string, entry: [string, string[]], handler: () => unknown) => Promise<void> })
         .processEntry("events:ts-kit-test", "ts-kit", "consumer-a", entry, () => { throw new HandlerError("fatal", "bad payload"); });
    } finally {
      console.warn = originalWarn;
    }

    assert.deepEqual(xadds[0].slice(0, 5), ["events:ts-kit-test:dlq", "MAXLEN", "~", configuredStreamMaxLen(), "*"]);
  });

  it("defaults to java-kit's cap and env var name", () => {
    assert.equal(DEFAULT_STREAM_MAXLEN, 10_000);
    assert.equal(STREAM_MAXLEN_ENV, "EVENT_STREAM_MAXLEN");
  });

  it("honours a configured override", () => {
    assert.equal(streamMaxLen("2500"), 2500);
    assert.equal(streamMaxLen(" 750 "), 750);
  });

  it("falls back to the default when unset, blank, non-numeric or non-positive", () => {
    // Falling back beats trimming to zero: a cap of 0 would discard every event.
    const warnings: unknown[] = [];
    const originalWarn = console.warn;
    console.warn = (value?: unknown) => { warnings.push(value); };
    try {
      assert.equal(streamMaxLen(undefined), DEFAULT_STREAM_MAXLEN);
      assert.equal(streamMaxLen(""), DEFAULT_STREAM_MAXLEN);
      assert.equal(streamMaxLen("   "), DEFAULT_STREAM_MAXLEN);
      assert.equal(streamMaxLen("not-a-number"), DEFAULT_STREAM_MAXLEN);
      assert.equal(streamMaxLen("0"), DEFAULT_STREAM_MAXLEN);
      assert.equal(streamMaxLen("-5"), DEFAULT_STREAM_MAXLEN);
      assert.equal(streamMaxLen("12.5"), DEFAULT_STREAM_MAXLEN);
    } finally {
      console.warn = originalWarn;
    }

    assert.equal(warnings.length, 4);
    assert.match(String(warnings[0]), /is not a positive integer/u);
  });
});
