/**
 * Outbound trace-context propagation, in the style of post-sales'
 * OutboundTracePropagationTest: a real local HTTP server records the headers it
 * receives, and the assertion is that the request carried the active span's
 * W3C traceparent.
 *
 * This is the regression that matters most for TypeScript: Node's global
 * `fetch` is undici and `HttpInstrumentation` does not patch it, so a plain
 * `fetch` sends no traceparent no matter how the SDK is configured. These tests
 * pin both halves -- plain `fetch` sends nothing, `tracedFetch` sends the
 * active span's context.
 */

import assert from "node:assert/strict";
import { createServer, type IncomingHttpHeaders, type Server } from "node:http";
import { after, before, beforeEach, describe, it } from "node:test";

import { context as apiContext, trace, type Span } from "@opentelemetry/api";
import { InMemorySpanExporter } from "@opentelemetry/sdk-trace-base";

import { initOpenTelemetry } from "./observability.js";
import { outboundTraceHeaders, tracedFetch, withTraceContext } from "./outbound.js";

let server: Server;
let baseUrl: string;
const captured: IncomingHttpHeaders[] = [];

function traceIdOf(traceparent: string | undefined): string | undefined {
  return traceparent?.split("-")[1];
}

/** Run `body` with `span` as the ambient active span, exactly as a server
 * handler would, then end the span. */
async function withActiveSpan(name: string, body: (span: Span) => Promise<void>): Promise<Span> {
  const span = trace.getTracer("ts-kit-outbound-test").startSpan(name);
  try {
    await apiContext.with(trace.setSpan(apiContext.active(), span), () => body(span));
  } finally {
    span.end();
  }
  return span;
}

before(async () => {
  process.env.OTEL_TRACES_EXPORTER = "otlp";
  process.env.OTEL_SERVICE_NAME = "ts-kit-outbound-test";
  // The SDK must be started for the propagator to be registered; the exporter
  // is in-memory so nothing leaves the process.
  initOpenTelemetry({ serviceName: "ts-kit-outbound-test", spanExporter: new InMemorySpanExporter() });
  server = createServer((request, response) => {
    captured.push(request.headers);
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ ok: true }));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address === "object");
  baseUrl = `http://127.0.0.1:${address.port}`;
});

after(async () => {
  await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
});

beforeEach(() => {
  captured.length = 0;
});

describe("outbound fetch trace propagation", () => {
  it("carries the active span's traceparent", async () => {
    const span = await withActiveSpan("outbound", async () => {
      const response = await tracedFetch(`${baseUrl}/api/v1/journey-orders`, {
        method: "POST",
        headers: { "content-type": "application/json", "Idempotency-Key": "idem-1", "X-Correlation-Id": "corr-1" },
        body: JSON.stringify({ a: 1 }),
      });
      assert.equal(response.status, 200);
    });

    const headers = captured.at(-1);
    assert.ok(headers, "the stub server must have received a request");
    assert.ok(headers.traceparent, "outbound request must carry a traceparent");
    assert.equal(traceIdOf(headers.traceparent as string), span.spanContext().traceId);
    // The traceparent must name this span, not merely share its trace: the
    // callee's server span parents to the span id in the header.
    assert.ok((headers.traceparent as string).includes(span.spanContext().spanId));
    // The headers the waitlist call sites already set must survive untouched.
    assert.equal(headers["idempotency-key"], "idem-1");
    assert.equal(headers["x-correlation-id"], "corr-1");
    assert.equal(headers["content-type"], "application/json");
  });

  it("documents that a plain global fetch sends no traceparent", async () => {
    // This is the bug tracedFetch exists to fix: HttpInstrumentation patches
    // node:http/node:https, and undici (global fetch) is not among them.
    await withActiveSpan("plain-fetch", async () => {
      await fetch(`${baseUrl}/plain`, { method: "GET" });
    });
    assert.equal(captured.at(-1)?.traceparent, undefined);
  });

  it("emits a fresh traceparent per call from one client", async () => {
    const first = await withActiveSpan("first", async () => {
      await tracedFetch(`${baseUrl}/first`);
    });
    const second = await withActiveSpan("second", async () => {
      await tracedFetch(`${baseUrl}/second`);
    });

    const firstSpanId = first.spanContext().spanId;
    const secondSpanId = second.spanContext().spanId;
    assert.notEqual(firstSpanId, secondSpanId);
    assert.ok((captured.at(-2)?.traceparent as string | undefined)?.includes(firstSpanId));
    assert.ok((captured.at(-1)?.traceparent as string | undefined)?.includes(secondSpanId));
  });

  it("sends no traceparent without an active span", async () => {
    await tracedFetch(`${baseUrl}/no-span`);
    assert.equal(captured.at(-1)?.traceparent, undefined);
  });

  it("propagates through a Request object", async () => {
    const span = await withActiveSpan("request-object", async () => {
      await tracedFetch(new Request(`${baseUrl}/request-object`, { headers: { "Idempotency-Key": "idem-2" } }));
    });
    const headers = captured.at(-1);
    assert.equal(traceIdOf(headers?.traceparent as string | undefined), span.spanContext().traceId);
    assert.equal(headers?.["idempotency-key"], "idem-2");
  });

  it("replaces a stale inbound traceparent instead of duplicating it", async () => {
    await withActiveSpan("stale", async (span) => {
      const merged = withTraceContext({
        traceparent: "00-11111111111111111111111111111111-2222222222222222-01",
        "Idempotency-Key": "idem-3",
      });
      assert.equal(traceIdOf(merged.get("traceparent") ?? undefined), span.spanContext().traceId);
      assert.equal(merged.get("idempotency-key"), "idem-3");
    });
  });

  it("returns no headers when tracing is disabled", () => {
    const previous = process.env.OTEL_TRACES_EXPORTER;
    delete process.env.OTEL_TRACES_EXPORTER;
    try {
      assert.deepEqual(outboundTraceHeaders(), {});
    } finally {
      if (previous === undefined) {
        delete process.env.OTEL_TRACES_EXPORTER;
      } else {
        process.env.OTEL_TRACES_EXPORTER = previous;
      }
    }
  });
});
