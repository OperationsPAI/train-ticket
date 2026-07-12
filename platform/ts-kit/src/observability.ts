import process from "node:process";

import { context, createTraceState, isSpanContextValid, SpanKind, SpanStatusCode, trace, TraceFlags, type Context, type Span, type SpanOptions, type Tracer } from "@opentelemetry/api";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-grpc";
import { HttpInstrumentation } from "@opentelemetry/instrumentation-http";
import { FastifyInstrumentation } from "@opentelemetry/instrumentation-fastify";
import { Resource } from "@opentelemetry/resources";
import { NodeSDK } from "@opentelemetry/sdk-node";
import { SimpleSpanProcessor, type SpanExporter } from "@opentelemetry/sdk-trace-base";
import { ATTR_SERVICE_NAME } from "@opentelemetry/semantic-conventions";

let sdk: NodeSDK | undefined;

export type InitOpenTelemetryOptions = Readonly<{
  serviceName?: string;
  spanExporter?: SpanExporter;
}>;

export function otelTracingEnabled(): boolean {
  const exporter = process.env.OTEL_TRACES_EXPORTER?.trim().toLowerCase();
  return Boolean(exporter && exporter !== "none");
}

export function initOpenTelemetry(options: InitOpenTelemetryOptions = {}): NodeSDK | undefined {
  if (!otelTracingEnabled() && !options.spanExporter) {
    return undefined;
  }
  if (sdk) {
    return sdk;
  }
  const serviceName = options.serviceName ?? process.env.OTEL_SERVICE_NAME ?? "train-ticket-service";
  // A custom exporter (tests) and the default OTLP exporter share one code
  // path: instrumentations must register either way, or HTTP spans exist
  // only in production and the in-memory assertion proves nothing.
  sdk = new NodeSDK({
    resource: new Resource({ [ATTR_SERVICE_NAME]: serviceName }),
    ...(options.spanExporter
      ? { spanProcessors: [new SimpleSpanProcessor(options.spanExporter)] }
      : { traceExporter: new OTLPTraceExporter() }),
    instrumentations: [
      new HttpInstrumentation(),
      new FastifyInstrumentation(),
    ],
  });
  sdk.start();
  return sdk;
}

export function getOpenTelemetryTracer(serviceName?: string): Tracer | undefined {
  if (!otelTracingEnabled()) {
    return undefined;
  }
  return trace.getTracer(serviceName ?? process.env.OTEL_SERVICE_NAME ?? "train-ticket-service");
}

export function activeTraceContext(): Readonly<{ traceparent: string; tracestate?: string }> | undefined {
  if (!otelTracingEnabled()) {
    return undefined;
  }
  const spanContext = trace.getActiveSpan()?.spanContext();
  if (!spanContext || !isSpanContextValid(spanContext)) {
    return undefined;
  }
  const traceparent = `00-${spanContext.traceId}-${spanContext.spanId}-${(spanContext.traceFlags & 0xff).toString(16).padStart(2, "0")}`;
  const tracestate = spanContext.traceState?.serialize();
  return tracestate ? { traceparent, tracestate } : { traceparent };
}

export function remoteTraceContext(traceparent: string | undefined, tracestate?: string): Context | undefined {
  const spanContext = parseTraceparent(traceparent, tracestate);
  return spanContext ? trace.setSpanContext(context.active(), spanContext) : undefined;
}

export function startConsumerSpan(attributes: MessagingConsumerSpanAttributes): Span | undefined {
  if (!otelTracingEnabled()) {
    return undefined;
  }
  const tracer = trace.getTracer(process.env.OTEL_SERVICE_NAME ?? "train-ticket-service");
  const spanOptions: SpanOptions = {
    kind: SpanKind.CONSUMER,
    attributes: {
      "messaging.system": "redis",
      "messaging.operation": "process",
      "messaging.destination.name": attributes.stream,
      "messaging.consumer.group.name": attributes.consumerGroup,
      "messaging.train_ticket.stream": attributes.stream,
      "messaging.train_ticket.consumerGroup": attributes.consumerGroup,
      "messaging.train_ticket.eventId": attributes.eventId,
      "messaging.train_ticket.eventType": attributes.eventType,
      "messaging.train_ticket.correlationId": attributes.correlationId,
    },
  };
  return tracer.startSpan(`${attributes.consumerGroup} process ${attributes.eventType}`, spanOptions, attributes.parentContext ?? context.active());
}

export type MessagingConsumerSpanAttributes = Readonly<{
  stream: string;
  consumerGroup: string;
  eventId: string;
  eventType: string;
  correlationId: string;
  parentContext?: Context;
}>;

export function endSpanWithError(span: Span | undefined, error: unknown): void {
  if (!span) {
    return;
  }
  if (error instanceof Error) {
    span.recordException(error);
    span.setStatus({ code: SpanStatusCode.ERROR, message: error.name || "Error" });
  } else {
    span.setStatus({ code: SpanStatusCode.ERROR, message: String(error || "Handler failed") });
  }
  span.end();
}

export function endSpan(span: Span | undefined): void {
  span?.end();
}

export function markSpanError(span: Span | undefined, message: string): void {
  span?.setStatus({ code: SpanStatusCode.ERROR, message });
}

function parseTraceparent(traceparent: string | undefined, tracestate?: string) {
  if (!traceparent) {
    return undefined;
  }
  const parts = traceparent.split("-");
  if (parts.length !== 4) {
    return undefined;
  }
  const [version, traceId, spanId, flags] = parts;
  if (version !== "00" || !/^[0-9a-f]{32}$/.test(traceId) || !/^[0-9a-f]{16}$/.test(spanId) || !/^[0-9a-f]{2}$/.test(flags)) {
    return undefined;
  }
  if (traceId === "00000000000000000000000000000000" || spanId === "0000000000000000") {
    return undefined;
  }
  const spanContext = {
    traceId,
    spanId,
    traceFlags: Number.parseInt(flags, 16) & TraceFlags.SAMPLED,
    traceState: tracestate ? createTraceState(tracestate) : undefined,
    isRemote: true,
  };
  return isSpanContextValid(spanContext) ? spanContext : undefined;
}
