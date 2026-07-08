import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { InMemorySpanExporter } from "@opentelemetry/sdk-trace-base";

import { createEventEnvelope, InMemoryEventSubscriber } from "./messaging.js";
import { initOpenTelemetry } from "./observability.js";

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
    const exporter = new InMemorySpanExporter();
    const sdk = initOpenTelemetry({ serviceName: "ts-kit-test", spanExporter: exporter });
    try {
      const subscriber = new InMemoryEventSubscriber();
      const envelope = createEventEnvelope({ eventType: "ExampleCreated", producer: "ts-kit-test", payload: {} });
      await subscriber.subscribe(["events:ts-kit-test"], "ts-kit", "consumer-a", () => undefined);
      assert.equal(await subscriber.simulateEvent(envelope), "ack");

      await (sdk as unknown as { forceFlush?: () => Promise<void> } | undefined)?.forceFlush?.();
      const spans = exporter.getFinishedSpans();
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
});
