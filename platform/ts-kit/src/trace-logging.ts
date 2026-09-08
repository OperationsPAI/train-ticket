/**
 * Publishes the current span's ids onto every log line so logs can be joined
 * to traces.
 *
 * WHY THIS EXISTS
 * ---------------
 * The platform had full trace propagation (W3C traceparent across HTTP and
 * through event envelopes) and structured logs, and no way to get from one to
 * the other: nothing put a trace id where a log line could see it, and no log
 * format printed one. So a line saying a refund was priced at zero could not be
 * tied to the trace that produced it, and a trace showing a failed span could
 * not be tied to the line explaining why. Diagnosing the zero-refund chain made
 * it concrete: five services, each taking a silent early return, correlated by
 * matching wall-clock timestamps by hand across five `kubectl logs` calls.
 *
 * This is the TypeScript half of the convention java-kit's TraceLoggingContext
 * established. `trace_id`/`span_id` are the field names the OpenTelemetry log
 * appenders use, so a service that later adopts a real structured logger gets
 * the same names rather than a second convention.
 *
 * TWO SOURCES, IN THIS ORDER
 * --------------------------
 * 1. An explicitly bound scope, held in an AsyncLocalStorage -- the direct
 *    analogue of SLF4J's MDC. Needed because ts-kit's event-consumer span is
 *    created with `tracer.startSpan()` and never made current, so
 *    `trace.getActiveSpan()` returns nothing at all inside an event handler.
 * 2. Otherwise the active span, which covers the inbound HTTP path for free:
 *    `@opentelemetry/instrumentation-http` and `-fastify` do activate their
 *    server span, so a request handler's lines find it with no wiring here.
 *
 * An explicit bind wins over the ambient span: inside a consumer scope the
 * handler's own trace is the answer, and must not be shadowed by whatever span
 * some other instrumentation happens to have activated mid-handler.
 *
 * WHY run() AND NOT AN enterWith()/close() HANDLE
 * ----------------------------------------------
 * java-kit returns an AutoCloseable because try-with-resources is how Java
 * scopes things. Node's equivalent is `AsyncLocalStorage.run`, and it is not
 * merely stylistic: `enterWith` mutates the *ambient* store, so two consumer
 * loops interleaving on the event loop would overwrite each other's ids and
 * publish one trace's id onto the other's log lines. `run` confines the binding
 * to one async call tree, which is exactly the property being modelled.
 */

import { AsyncLocalStorage } from "node:async_hooks";

import { isSpanContextValid, trace, type Span } from "@opentelemetry/api";

export const TRACE_ID_FIELD = "trace_id";
export const SPAN_ID_FIELD = "span_id";

/**
 * `span_id` is optional because a batch scope knows the trace but not a single
 * span. An unknown field is omitted entirely rather than emitted blank, for the
 * same reason an invalid span is never bound at all.
 */
export type TraceLoggingIds = Readonly<{ trace_id: string; span_id?: string }>;

const traceLoggingStore = new AsyncLocalStorage<TraceLoggingIds>();

/**
 * Run `body` with `span`'s ids on every log line it produces.
 *
 * Nothing is bound for an absent or invalid span, and in that case `body` runs
 * in the *enclosing* binding rather than in a cleared one. That distinction is
 * the whole point: an event handler that makes an HTTP call would otherwise
 * lose its trace id the moment the inner no-op scope ended, and log the rest of
 * its work -- the lines most likely to explain a failure, since they come after
 * it -- uncorrelated.
 *
 * The invalid-span case is not hypothetical. An unsampled or absent span yields
 * an all-zero trace id, and logging that is worse than logging nothing: it
 * looks like a real id and joins every unrelated line in the log together.
 */
export function runWithTraceLoggingContext<T>(span: Span | undefined, body: () => T): T {
  const spanContext = span?.spanContext();
  if (!spanContext || !isSpanContextValid(spanContext)) {
    return body();
  }
  return traceLoggingStore.run({ [TRACE_ID_FIELD]: spanContext.traceId, [SPAN_ID_FIELD]: spanContext.spanId }, body);
}

/**
 * Run a batch handler with the ids covering all of `spans`.
 *
 * A batch handler is one call over N events that may each belong to a different
 * trace, so there is usually no single span to name. When the batch does share
 * one trace -- the common case, a burst produced by one upstream request -- the
 * trace id is unambiguous and worth binding; the span id is not, so it is left
 * off rather than picking one of N arbitrarily. A mixed batch binds nothing,
 * because naming one of the traces would be a lie about the other lines.
 */
export function runWithTraceLoggingContextForBatch<T>(spans: readonly (Span | undefined)[], body: () => T): T {
  const traceIds = new Set<string>();
  for (const span of spans) {
    const spanContext = span?.spanContext();
    if (!spanContext || !isSpanContextValid(spanContext)) {
      return body();
    }
    traceIds.add(spanContext.traceId);
  }
  const [traceId] = traceIds;
  if (traceIds.size !== 1 || !traceId) {
    return body();
  }
  return traceLoggingStore.run({ [TRACE_ID_FIELD]: traceId }, body);
}

/** The ids a log line written right now should carry, or undefined. */
export function currentTraceLoggingIds(): TraceLoggingIds | undefined {
  const bound = traceLoggingStore.getStore();
  if (bound) {
    return bound;
  }
  const spanContext = trace.getActiveSpan()?.spanContext();
  if (!spanContext || !isSpanContextValid(spanContext)) {
    return undefined;
  }
  return { [TRACE_ID_FIELD]: spanContext.traceId, [SPAN_ID_FIELD]: spanContext.spanId };
}

/**
 * Add the ids to one console call's arguments.
 *
 * The kit and the services log both objects (`console.warn({ service, message
 * })`) and plain strings. An object argument gets the ids merged in so the line
 * stays one structured record; anything else -- a string, an Error -- gets them
 * appended as a trailing object, because rewriting the caller's value would be
 * lossy. Caller-supplied fields win: an explicit trace_id in the record is
 * deliberate and outranks the ambient one.
 *
 * With no ids the arguments come back untouched, so a service running without
 * tracing logs exactly what it logged before.
 */
export function withTraceLoggingFields(args: readonly unknown[]): unknown[] {
  const ids = currentTraceLoggingIds();
  if (!ids) {
    return [...args];
  }
  const [first, ...rest] = args;
  if (isPlainObject(first)) {
    return [{ ...ids, ...first }, ...rest];
  }
  return [...args, { ...ids }];
}

const TRACE_LOGGING_WRAPPER = Symbol.for("trainticket.traceLoggingConsole");
const CONSOLE_METHODS = ["debug", "error", "info", "log", "warn"] as const;

type ConsoleMethod = (typeof CONSOLE_METHODS)[number];
type ConsoleLike = Record<ConsoleMethod, (...args: unknown[]) => void>;

/**
 * Make every console line carry the ids; returns a function that undoes it.
 *
 * WHY THE CONSOLE AND NOT A LOGGER OBJECT
 * ---------------------------------------
 * The TypeScript services have no logger to hang this on: all seven build
 * Fastify with `logger: false` and log through bare `console.*`, in the kit and
 * in service code alike. Introducing a logger would mean editing every call
 * site in seven services and would still miss the kit's own lines. Wrapping the
 * console is the same move java-kit makes by supplying a log *pattern* instead
 * of editing log statements: the ids reach every line, including lines written
 * by code that knows nothing about tracing.
 *
 * Idempotent -- installing twice does not double-wrap -- and the returned
 * restore puts back exactly the functions that were replaced, so a test that
 * stubs `console.warn` afterwards still sees its own stub.
 */
export function installTraceLoggingConsole(target: ConsoleLike = globalThis.console as unknown as ConsoleLike): () => void {
  const replaced: Partial<Record<ConsoleMethod, (...args: unknown[]) => void>> = {};
  for (const method of CONSOLE_METHODS) {
    const original = target[method];
    if (typeof original !== "function" || (original as { [TRACE_LOGGING_WRAPPER]?: boolean })[TRACE_LOGGING_WRAPPER]) {
      continue;
    }
    const wrapper = (...args: unknown[]): void => {
      original.apply(target, withTraceLoggingFields(args));
    };
    (wrapper as { [TRACE_LOGGING_WRAPPER]?: boolean })[TRACE_LOGGING_WRAPPER] = true;
    replaced[method] = original;
    target[method] = wrapper;
  }
  return () => {
    for (const [method, original] of Object.entries(replaced) as [ConsoleMethod, (...args: unknown[]) => void][]) {
      target[method] = original;
    }
  };
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}
