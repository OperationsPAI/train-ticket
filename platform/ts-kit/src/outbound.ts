/**
 * Outbound HTTP trace-context propagation for TypeScript services.
 *
 * Node's global `fetch` is undici, which `@opentelemetry/instrumentation-http`
 * does not patch -- it hooks `node:http` and `node:https` only. So every
 * `fetch` in a service sends no `traceparent` even with the SDK fully started,
 * and the callee opens a fresh trace. Rather than each call site building the
 * header by hand, services take `tracedFetch` from here: the propagation is
 * installed once, in the kit, and call sites get it by construction.
 *
 * `tracedFetch` is a drop-in for `fetch`: identical signature, identical
 * return value, and it delegates to the global `fetch` so it also picks up any
 * instrumentation that does patch undici if one is added later. When tracing is
 * off or no span is active it forwards the request byte-identically.
 */

import { context, propagation, trace, type Context } from "@opentelemetry/api";

import { activeTraceContext, otelTracingEnabled } from "./observability.js";

/** The W3C trace-context headers, the only ones this module ever sets. */
export const TRACE_CONTEXT_HEADERS = ["traceparent", "tracestate"] as const;

export type TracedFetch = typeof fetch;

/**
 * Return the active span's W3C trace-context headers, or an empty object when
 * there is nothing valid to propagate.
 *
 * Uses the global propagator when one is registered (the NodeSDK registers W3C
 * by default) and falls back to formatting the active span context directly, so
 * a service that has not started the SDK's propagator still propagates.
 */
export function outboundTraceHeaders(activeContext: Context = context.active()): Record<string, string> {
  if (!otelTracingEnabled()) {
    return {};
  }
  const spanContext = trace.getSpan(activeContext)?.spanContext();
  if (!spanContext) {
    return {};
  }
  const carrier: Record<string, string> = {};
  propagation.inject(activeContext, carrier);
  const injected = pickTraceHeaders(carrier);
  if (injected.traceparent) {
    return injected;
  }
  // No global propagator: fall back to the kit's own W3C formatter, which is
  // the same one the Redis envelope injection uses.
  const fallback = activeTraceContext();
  return fallback ? { ...fallback } : {};
}

/**
 * Add the active trace context to `headers`, replacing any existing
 * `traceparent`/`tracestate`, and return a new `Headers`.
 *
 * A stale inbound `traceparent` copied onto an outbound request would parent
 * the callee to the wrong span, so it is overwritten rather than merged. Every
 * other header -- `X-Correlation-Id`, `Idempotency-Key`, `content-type` -- is
 * preserved exactly.
 */
export function withTraceContext(headers?: HeadersInit): Headers {
  const merged = new Headers(headers);
  const injected = outboundTraceHeaders();
  for (const name of TRACE_CONTEXT_HEADERS) {
    merged.delete(name);
  }
  for (const [name, value] of Object.entries(injected)) {
    if (value) {
      merged.set(name, value);
    }
  }
  return merged;
}

/**
 * A `fetch` that carries the active span's W3C trace context.
 *
 * Drop-in replacement for the global `fetch`. Both call forms are supported
 * (`tracedFetch(url, init)` and `tracedFetch(request)`), and the headers are
 * resolved per call, so a single long-lived client emits the correct
 * `traceparent` for whichever span is active at call time.
 */
export const tracedFetch: TracedFetch = ((input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const injected = outboundTraceHeaders();
  if (Object.keys(injected).length === 0) {
    // Nothing to propagate: forward untouched so a disabled-tracing request is
    // byte-identical to a plain fetch.
    return globalThis.fetch(input as RequestInfo, init);
  }
  if (input instanceof Request && init === undefined) {
    // A Request carries its own immutable headers; clone it with the trace
    // context merged in rather than mutating the caller's object.
    return globalThis.fetch(new Request(input, { headers: withTraceContext(input.headers) }));
  }
  const headers = withTraceContext(init?.headers ?? (input instanceof Request ? input.headers : undefined));
  return globalThis.fetch(input as RequestInfo, { ...init, headers });
}) as TracedFetch;

function pickTraceHeaders(carrier: Record<string, string>): Record<string, string> {
  const picked: Record<string, string> = {};
  for (const [name, value] of Object.entries(carrier)) {
    const lower = name.toLowerCase();
    if ((TRACE_CONTEXT_HEADERS as readonly string[]).includes(lower) && value) {
      picked[lower] = value;
    }
  }
  return picked;
}
