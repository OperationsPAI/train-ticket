import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { context, INVALID_SPAN_CONTEXT, trace, type Span } from "@opentelemetry/api";
import { InMemorySpanExporter } from "@opentelemetry/sdk-trace-base";

import { createEventEnvelope, InMemoryEventSubscriber, RedisEventSubscriber, type EventEnvelope } from "./messaging.js";
import { initOpenTelemetry } from "./observability.js";
import {
  currentTraceLoggingIds,
  installTraceLoggingConsole,
  runWithTraceLoggingContext,
  runWithTraceLoggingContextForBatch,
  SPAN_ID_FIELD,
  TRACE_ID_FIELD,
} from "./trace-logging.js";

const ZERO_TRACE_ID = "00000000000000000000000000000000";
const TRACE = "0af7651916cd43dd8448eb211c80319c";
const SPAN = "b7ad6b7169203331";
const OTHER_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
const OTHER_SPAN = "00f067aa0ba902b7";

function sampledSpan(traceId: string, spanId: string): Span {
  return trace.wrapSpanContext({ traceId, spanId, traceFlags: 1 });
}

function invalidSpan(): Span {
  return trace.wrapSpanContext(INVALID_SPAN_CONTEXT);
}

type Recorder = { lines: unknown[][]; console: Record<"debug" | "error" | "info" | "log" | "warn", (...args: unknown[]) => void> };

function recordingConsole(method: "debug" | "error" | "info" | "log" | "warn" = "warn"): Recorder {
  const lines: unknown[][] = [];
  const noop = () => undefined;
  const record = (...args: unknown[]) => { lines.push(args); };
  return {
    lines,
    console: {
      debug: method === "debug" ? record : noop,
      error: method === "error" ? record : noop,
      info: method === "info" ? record : noop,
      log: method === "log" ? record : noop,
      warn: method === "warn" ? record : noop,
    },
  };
}

function withSdkStarted<T>(body: () => T): T {
  const previousExporter = process.env.OTEL_TRACES_EXPORTER;
  const previousService = process.env.OTEL_SERVICE_NAME;
  process.env.OTEL_TRACES_EXPORTER = "otlp";
  process.env.OTEL_SERVICE_NAME = "ts-kit-trace-logging";
  initOpenTelemetry({ serviceName: "ts-kit-trace-logging", spanExporter: new InMemorySpanExporter() });
  try {
    return body();
  } finally {
    if (previousExporter === undefined) { delete process.env.OTEL_TRACES_EXPORTER; } else { process.env.OTEL_TRACES_EXPORTER = previousExporter; }
    if (previousService === undefined) { delete process.env.OTEL_SERVICE_NAME; } else { process.env.OTEL_SERVICE_NAME = previousService; }
  }
}

/**
 * The point of this module is that a log line can be joined to a trace. These
 * tests pin the two ways that silently stops being true: ids that never arrive,
 * and ids that get dropped while the request is still running.
 */
describe("trace logging context", () => {
  it("binds trace and span ids for a valid span", () => {
    runWithTraceLoggingContext(sampledSpan(TRACE, SPAN), () => {
      assert.deepEqual(currentTraceLoggingIds(), { [TRACE_ID_FIELD]: TRACE, [SPAN_ID_FIELD]: SPAN });
    });
    assert.equal(currentTraceLoggingIds(), undefined);
  });

  it("binds nothing for an invalid span", () => {
    // An absent or non-recording span yields an all-zero trace id. Logging that
    // is worse than logging nothing: it looks like a real id and joins every
    // unrelated line in the log together.
    runWithTraceLoggingContext(invalidSpan(), () => {
      assert.equal(currentTraceLoggingIds(), undefined);
    });
  });

  it("binds nothing for an undefined span", () => {
    runWithTraceLoggingContext(undefined, () => {
      assert.equal(currentTraceLoggingIds(), undefined);
    });
  });

  it("keeps the outer scope ids across an inner no-op scope", () => {
    // The regression this guards: an event handler binds a scope, calls
    // something that opens a no-op scope, and when the inner scope ends the
    // handler loses its trace id for every remaining line -- exactly the lines
    // most likely to explain a failure, since they come after the work.
    runWithTraceLoggingContext(sampledSpan(TRACE, SPAN), () => {
      runWithTraceLoggingContext(invalidSpan(), () => {
        assert.equal(currentTraceLoggingIds()?.[TRACE_ID_FIELD], TRACE);
      });
      assert.equal(
      currentTraceLoggingIds()?.[TRACE_ID_FIELD],
      TRACE,
      "the outer scope trace id must survive an inner no-op scope",
      );
    });
  });

  it("restores the enclosing ids after a nested valid scope", () => {
    runWithTraceLoggingContext(sampledSpan(TRACE, SPAN), () => {
      runWithTraceLoggingContext(sampledSpan(OTHER_TRACE, OTHER_SPAN), () => {
        assert.deepEqual(currentTraceLoggingIds(), { [TRACE_ID_FIELD]: OTHER_TRACE, [SPAN_ID_FIELD]: OTHER_SPAN });
      });
      assert.deepEqual(currentTraceLoggingIds(), { [TRACE_ID_FIELD]: TRACE, [SPAN_ID_FIELD]: SPAN });
    });
    assert.equal(currentTraceLoggingIds(), undefined);
  });

  it("keeps the outer ids across an inner no-op scope in async code", async () => {
    // The synchronous version above would still pass if the binding were
    // ambient rather than per-call-tree. Awaiting inside both scopes is what a
    // real handler does, and is where an ambient binding leaks.
    await runWithTraceLoggingContext(sampledSpan(TRACE, SPAN), async () => {
      await runWithTraceLoggingContext(undefined, async () => {
        await Promise.resolve();
        assert.equal(currentTraceLoggingIds()?.[TRACE_ID_FIELD], TRACE);
      });
      await Promise.resolve();
      assert.equal(currentTraceLoggingIds()?.[TRACE_ID_FIELD], TRACE);
    });
  });

  it("binds only the shared trace id for a single-trace batch", () => {
    // A batch handler is one call over N events; a span id would have to be one
    // of N picked arbitrarily, so only the trace they agree on is bound.
    runWithTraceLoggingContextForBatch([sampledSpan(TRACE, SPAN), sampledSpan(TRACE, OTHER_SPAN)], () => {
      assert.deepEqual(currentTraceLoggingIds(), { [TRACE_ID_FIELD]: TRACE });
    });
  });

  it("binds nothing for a batch spanning several traces", () => {
    runWithTraceLoggingContextForBatch([sampledSpan(TRACE, SPAN), sampledSpan(OTHER_TRACE, SPAN)], () => {
      assert.equal(currentTraceLoggingIds(), undefined);
    });
  });

  // These two need the SDK started: until it installs a context manager the
  // API's no-op one drops the value, so `context.with` would prove nothing.
  it("falls back to the active span when nothing is explicitly bound", () => {
    // This is the whole HTTP path: instrumentation-http activates the server
    // span, so a request handler's lines carry the ids with no wiring in the
    // kit at all.
    withSdkStarted(() => {
      context.with(trace.setSpan(context.active(), sampledSpan(TRACE, SPAN)), () => {
        assert.deepEqual(currentTraceLoggingIds(), { [TRACE_ID_FIELD]: TRACE, [SPAN_ID_FIELD]: SPAN });
      });
    });
  });

  it("ignores an active span with an all-zero context", () => {
    withSdkStarted(() => {
      context.with(trace.setSpan(context.active(), invalidSpan()), () => {
        assert.equal(currentTraceLoggingIds(), undefined);
      });
    });
  });
});

describe("trace logging console", () => {
  it("merges the ids into a structured log record", () => {
    const recorder = recordingConsole("warn");
    const restore = installTraceLoggingConsole(recorder.console);
    try {
      runWithTraceLoggingContext(sampledSpan(TRACE, SPAN), () => {
        recorder.console.warn({ service: "waitlist", message: "handler transient failure" });
      });
    } finally {
      restore();
    }
    assert.deepEqual(recorder.lines[0], [{
      [TRACE_ID_FIELD]: TRACE,
      [SPAN_ID_FIELD]: SPAN,
      service: "waitlist",
      message: "handler transient failure",
    }]);
  });

  it("appends the ids to a non-object log line", () => {
    const recorder = recordingConsole("info");
    const restore = installTraceLoggingConsole(recorder.console);
    try {
      runWithTraceLoggingContext(sampledSpan(TRACE, SPAN), () => {
        recorder.console.info("Ignoring unsupported notification trigger");
      });
    } finally {
      restore();
    }
    assert.deepEqual(recorder.lines[0], ["Ignoring unsupported notification trigger", { [TRACE_ID_FIELD]: TRACE, [SPAN_ID_FIELD]: SPAN }]);
  });

  it("leaves a log line untouched when there is no valid span", () => {
    // A service running with tracing off, or a startup line logged before any
    // span exists, must look exactly as it did before this existed.
    const recorder = recordingConsole("warn");
    const restore = installTraceLoggingConsole(recorder.console);
    try {
      recorder.console.warn({ message: "redis reconnecting" });
    } finally {
      restore();
    }
    assert.deepEqual(recorder.lines[0], [{ message: "redis reconnecting" }]);
  });

  it("never emits the all-zero trace id", () => {
    const recorder = recordingConsole("error");
    const restore = installTraceLoggingConsole(recorder.console);
    try {
      context.with(trace.setSpan(context.active(), invalidSpan()), () => {
        recorder.console.error({ message: "poll read failed" });
      });
    } finally {
      restore();
    }
    assert.equal(JSON.stringify(recorder.lines[0]).includes(ZERO_TRACE_ID), false);
  });

  it("does not double-wrap, and restore puts back the original function", () => {
    const recorder = recordingConsole("warn");
    const original = recorder.console.warn;
    const restoreOuter = installTraceLoggingConsole(recorder.console);
    const restoreInner = installTraceLoggingConsole(recorder.console);
    runWithTraceLoggingContext(sampledSpan(TRACE, SPAN), () => { recorder.console.warn({ message: "once" }); });
    restoreInner();
    restoreOuter();
    assert.equal(recorder.console.warn, original);
    assert.deepEqual(recorder.lines[0], [{ [TRACE_ID_FIELD]: TRACE, [SPAN_ID_FIELD]: SPAN, message: "once" }]);
  });
});

describe("trace ids on event consumer log lines", () => {
  function withTracingEnabled<T>(body: () => Promise<T>): Promise<T> {
    const previousExporter = process.env.OTEL_TRACES_EXPORTER;
    const previousService = process.env.OTEL_SERVICE_NAME;
    process.env.OTEL_TRACES_EXPORTER = "otlp";
    process.env.OTEL_SERVICE_NAME = "ts-kit-trace-logging";
    initOpenTelemetry({ serviceName: "ts-kit-trace-logging", spanExporter: new InMemorySpanExporter() });
    return body().finally(() => {
      if (previousExporter === undefined) { delete process.env.OTEL_TRACES_EXPORTER; } else { process.env.OTEL_TRACES_EXPORTER = previousExporter; }
      if (previousService === undefined) { delete process.env.OTEL_SERVICE_NAME; } else { process.env.OTEL_SERVICE_NAME = previousService; }
    });
  }

  it("puts the consumer span ids on lines the handler logs", async () => {
    const lines: unknown[][] = [];
    const originalWarn = console.warn;
    console.warn = (...args: unknown[]) => { lines.push(args); };
    try {
      await withTracingEnabled(async () => {
        const subscriber = new InMemoryEventSubscriber();
        const envelope = createEventEnvelope({ eventType: "ExampleCreated", producer: "ts-kit-test", payload: {} });
        await subscriber.subscribe(["events:ts-kit-test"], "ts-kit", "consumer-a", () => {
          // A handler logging exactly the way service code does: no tracing
          // awareness, nothing passed in.
          console.warn({ message: "handler saw the event" });
          return undefined;
        });
        assert.equal(await subscriber.simulateEvent(envelope), "ack");
      });
    } finally {
      console.warn = originalWarn;
    }
    const record = lines[0]?.[0] as Record<string, unknown> | undefined;
    assert.ok(record, "the handler's log line was not captured");
    assert.match(String(record[TRACE_ID_FIELD]), /^[0-9a-f]{32}$/);
    assert.notEqual(record[TRACE_ID_FIELD], ZERO_TRACE_ID);
    assert.match(String(record[SPAN_ID_FIELD]), /^[0-9a-f]{16}$/);
    assert.equal(record.message, "handler saw the event");
  });

  it("carries the producer's trace id onto the consumer's log lines", async () => {
    // The end-to-end claim: a line logged in the consuming service joins the
    // trace that started in the producing one. This is the case that used to
    // mean matching wall-clock timestamps across five `kubectl logs` runs.
    const lines: unknown[][] = [];
    const originalWarn = console.warn;
    console.warn = (...args: unknown[]) => { lines.push(args); };
    let producerTraceId = "";
    try {
      await withTracingEnabled(async () => {
        const producer = trace.getTracer("ts-kit-trace-logging").startSpan("producer");
        producerTraceId = producer.spanContext().traceId;
        let envelope: EventEnvelope | undefined;
        await context.with(trace.setSpan(context.active(), producer), async () => {
          envelope = createEventEnvelope({ eventType: "ExampleCreated", producer: "ts-kit-test", payload: {} });
        });
        producer.end();
        const subscriber = new InMemoryEventSubscriber();
        await subscriber.subscribe(["events:ts-kit-test"], "ts-kit", "consumer-a", () => {
          console.warn({ message: "consumer handled a remote-parented event" });
          return undefined;
        });
        assert.equal(await subscriber.simulateEvent(envelope!), "ack");
      });
    } finally {
      console.warn = originalWarn;
    }
    const record = lines[0]?.[0] as Record<string, unknown> | undefined;
    assert.ok(record);
    assert.equal(record[TRACE_ID_FIELD], producerTraceId);
  });

  it("leaves the subscriber's own DLQ log record unchanged when tracing is off", async () => {
    // The kit's own warn() calls go through the same wrapper. With no span
    // there must be no extra field, or every existing assertion on these
    // records would quietly start failing.
    const previous = process.env.OTEL_TRACES_EXPORTER;
    delete process.env.OTEL_TRACES_EXPORTER;
    const warnings: unknown[] = [];
    const originalWarn = console.warn;
    console.warn = (value?: unknown) => { warnings.push(value); };
    try {
      const redis = { xadd: async () => "1-1", xack: async () => 1 };
      const subscriber = new RedisEventSubscriber(redis as never);
      const envelope = createEventEnvelope({ eventType: "Poisoned", producer: "ts-kit-test", payload: {} });
      const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(envelope)]];
      await (subscriber as unknown as { processEntry: (s: string, g: string, c: string, e: [string, string[]], h: () => unknown) => Promise<void> })
      .processEntry("events:ts-kit-test", "ts-kit", "consumer-a", entry, () => "dlq");
    } finally {
      console.warn = originalWarn;
      if (previous === undefined) { delete process.env.OTEL_TRACES_EXPORTER; } else { process.env.OTEL_TRACES_EXPORTER = previous; }
    }
    const record = warnings[0] as Record<string, unknown>;
    assert.equal(record.message, "moving message to DLQ");
    assert.equal(TRACE_ID_FIELD in record, false);
  });
});

