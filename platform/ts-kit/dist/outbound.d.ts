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
import { type Context } from "@opentelemetry/api";
/** The W3C trace-context headers, the only ones this module ever sets. */
export declare const TRACE_CONTEXT_HEADERS: readonly ["traceparent", "tracestate"];
export type TracedFetch = typeof fetch;
/**
 * Return the active span's W3C trace-context headers, or an empty object when
 * there is nothing valid to propagate.
 *
 * Uses the global propagator when one is registered (the NodeSDK registers W3C
 * by default) and falls back to formatting the active span context directly, so
 * a service that has not started the SDK's propagator still propagates.
 */
export declare function outboundTraceHeaders(activeContext?: Context): Record<string, string>;
/**
 * Add the active trace context to `headers`, replacing any existing
 * `traceparent`/`tracestate`, and return a new `Headers`.
 *
 * A stale inbound `traceparent` copied onto an outbound request would parent
 * the callee to the wrong span, so it is overwritten rather than merged. Every
 * other header -- `X-Correlation-Id`, `Idempotency-Key`, `content-type` -- is
 * preserved exactly.
 */
export declare function withTraceContext(headers?: HeadersInit): Headers;
/**
 * A `fetch` that carries the active span's W3C trace context.
 *
 * Drop-in replacement for the global `fetch`. Both call forms are supported
 * (`tracedFetch(url, init)` and `tracedFetch(request)`), and the headers are
 * resolved per call, so a single long-lived client emits the correct
 * `traceparent` for whichever span is active at call time.
 */
export declare const tracedFetch: TracedFetch;
