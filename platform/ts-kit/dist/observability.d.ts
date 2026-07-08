import { type Span, type Tracer } from "@opentelemetry/api";
import { NodeSDK } from "@opentelemetry/sdk-node";
import { type SpanExporter } from "@opentelemetry/sdk-trace-base";
export type InitOpenTelemetryOptions = Readonly<{
    serviceName?: string;
    spanExporter?: SpanExporter;
}>;
export declare function otelTracingEnabled(): boolean;
export declare function initOpenTelemetry(options?: InitOpenTelemetryOptions): NodeSDK | undefined;
export declare function getOpenTelemetryTracer(serviceName?: string): Tracer | undefined;
export declare function startConsumerSpan(attributes: MessagingConsumerSpanAttributes): Span | undefined;
export type MessagingConsumerSpanAttributes = Readonly<{
    stream: string;
    consumerGroup: string;
    eventId: string;
    eventType: string;
    correlationId: string;
}>;
export declare function endSpanWithError(span: Span | undefined, error: unknown): void;
export declare function endSpan(span: Span | undefined): void;
export declare function markSpanError(span: Span | undefined, message: string): void;
