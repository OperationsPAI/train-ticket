import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { InMemorySpanExporter } from "@opentelemetry/sdk-trace-base";
import { SpanKind } from "@opentelemetry/api";

import { createEventEnvelope, InMemoryEventSubscriber } from "./messaging.js";
import { initOpenTelemetry } from "./observability.js";

const sharedExporter = new InMemorySpanExporter();

async function waitForSpans(exporter: InMemorySpanExporter, ready: (spans: ReturnType<InMemorySpanExporter["getFinishedSpans"]>) => boolean, timeoutMs = 2_000) {
  // NodeSDK resources carry async attributes, so exports land a tick
  // after span.end(); poll instead of asserting synchronously.
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const spans = exporter.getFinishedSpans();
    if (ready(spans)) {
      return spans;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  return exporter.getFinishedSpans();
}

describe("OpenTelemetry opt-in", () => {
  it("does not initialize without OTEL_TRACES_EXPORTER", async () => {
    const previous = process.env.OTEL_TRACES_EXPORTER;
    delete process.env.OTEL_TRACES_EXPORTER;
    try {
      assert.equal(initOpenTelemetry(), undefined);
      const subscriber = new InMemoryEventSubscriber();
      const envelope = createEventEnvelope({ eventType: "ExampleCreated", producer: "ts-kit-test", payload: {} });
      await subscriber.subscribe(["events:ts-kit-test"], "ts-kit", "consumer-a", () => undefined);
      assert.equal(await subscriber.simulateEvent(envelope), "ack");
    } finally {
      if (previous === undefined) {
        delete process.env.OTEL_TRACES_EXPORTER;
      } else {
        process.env.OTEL_TRACES_EXPORTER = previous;
      }
    }
  });

  it("creates event consumer spans with an in-memory exporter when enabled", async () => {
    const previousExporter = process.env.OTEL_TRACES_EXPORTER;
    const previousService = process.env.OTEL_SERVICE_NAME;
    process.env.OTEL_TRACES_EXPORTER = "otlp";
    process.env.OTEL_SERVICE_NAME = "ts-kit-test";
    const exporter = sharedExporter;
    const sdk = initOpenTelemetry({ serviceName: "ts-kit-test", spanExporter: exporter });
    try {
      const subscriber = new InMemoryEventSubscriber();
      const envelope = createEventEnvelope({ eventType: "ExampleCreated", producer: "ts-kit-test", payload: {} });
      await subscriber.subscribe(["events:ts-kit-test"], "ts-kit", "consumer-a", () => undefined);
      assert.equal(await subscriber.simulateEvent(envelope), "ack");

      await (sdk as unknown as { forceFlush?: () => Promise<void> } | undefined)?.forceFlush?.();
      const spans = await waitForSpans(exporter, (candidates) => candidates.some((candidate) => candidate.name === "in-memory process ExampleCreated"));
      const span = spans.find((candidate) => candidate.name === "in-memory process ExampleCreated");
      assert.ok(span);
      assert.equal(span.attributes["messaging.train_ticket.stream"], "in-memory");
      assert.equal(span.attributes["messaging.train_ticket.consumerGroup"], "in-memory");
      assert.equal(span.attributes["messaging.train_ticket.eventId"], envelope.eventId);
      assert.equal(span.attributes["messaging.train_ticket.eventType"], envelope.eventType);
      assert.equal(span.attributes["messaging.train_ticket.correlationId"], envelope.correlationId);
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

  it("creates HTTP server spans with the in-memory exporter when enabled", async () => {
    const previousExporter = process.env.OTEL_TRACES_EXPORTER;
    const previousService = process.env.OTEL_SERVICE_NAME;
    process.env.OTEL_TRACES_EXPORTER = "otlp";
    process.env.OTEL_SERVICE_NAME = "ts-kit-test";
    initOpenTelemetry({ serviceName: "ts-kit-test", spanExporter: sharedExporter });
    sharedExporter.reset();
    try {
      // fastify (CJS) reaches http via require(), which is what the
      // instrumentation patches; mirror that path here. A bare ESM
      // import of node:http bypasses require-in-the-middle and would
      // assert nothing real.
      const { createRequire } = await import("node:module");
      const cjsRequire = createRequire(import.meta.url);
      const http = cjsRequire("http") as typeof import("node:http");
      const server = http.createServer((_req, res) => {
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      });
      await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
      const address = server.address();
      assert.ok(address && typeof address === "object");
      const status = await new Promise<number>((resolve, reject) => {
        http.get({ host: "127.0.0.1", port: address.port, path: "/health" }, (res) => {
          res.resume();
          res.on("end", () => resolve(res.statusCode ?? 0));
        }).on("error", reject);
      });
      await new Promise<void>((resolve) => server.close(() => resolve()));
      assert.equal(status, 200);
      const spans = await waitForSpans(sharedExporter, (candidates) => candidates.some((candidate) => candidate.kind === SpanKind.SERVER));
      const serverSpan = spans.find((candidate) => candidate.kind === SpanKind.SERVER);
      assert.ok(serverSpan, `expected an HTTP server span, got: ${spans.map((s) => `${s.name}/${s.kind}`).join(", ") || "<none>"}`);
      assert.equal(serverSpan.attributes["http.method"] ?? serverSpan.attributes["http.request.method"], "GET");
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
});
