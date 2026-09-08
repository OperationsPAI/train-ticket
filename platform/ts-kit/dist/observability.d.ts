import { type Context, type Span, type Tracer } from "@opentelemetry/api";
import { NodeSDK } from "@opentelemetry/sdk-node";
import { type SpanExporter } from "@opentelemetry/sdk-trace-base";
export type InitOpenTelemetryOptions = Readonly<{
    serviceName?: string;
    spanExporter?: SpanExporter;
}>;
export declare function otelTracingEnabled(): boolean;
/**
 * Start the SDK and put trace_id/span_id on every log line.
 *
 * The console wrapper goes in here because every service already calls this
 * once at boot, so the ids reach every line without a single service edit --
 * the same move java-kit makes by supplying a log pattern instead of rewriting
 * log statements. It stays inside the tracing-enabled branch: with tracing off
 * there are no ids to add and logs stay byte-identical to before.
 */
export declare function initOpenTelemetry(options?: InitOpenTelemetryOptions): NodeSDK | undefined;
export declare function getOpenTelemetryTracer(serviceName?: string): Tracer | undefined;
export declare function activeTraceContext(): Readonly<{
    traceparent: string;
    tracestate?: string;
}> | undefined;
export declare function remoteTraceContext(traceparent: string | undefined, tracestate?: string): Context | undefined;
export declare function startConsumerSpan(attributes: MessagingConsumerSpanAttributes): Span | undefined;
export type MessagingConsumerSpanAttributes = Readonly<{
    stream: string;
    consumerGroup: string;
    eventId: string;
    eventType: string;
    correlationId: string;
    parentContext?: Context;
}>;
export declare function endSpanWithError(span: Span | undefined, error: unknown): void;
export declare function endSpan(span: Span | undefined): void;
export declare function markSpanError(span: Span | undefined, message: string): void;
