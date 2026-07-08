import process from "node:process";

import { context, SpanKind, SpanStatusCode, trace, type Span, type SpanOptions, type Tracer } from "@opentelemetry/api";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-grpc";
import { HttpInstrumentation } from "@opentelemetry/instrumentation-http";
import { FastifyInstrumentation } from "@opentelemetry/instrumentation-fastify";
import { Resource } from "@opentelemetry/resources";
import { NodeSDK } from "@opentelemetry/sdk-node";
import { BasicTracerProvider, SimpleSpanProcessor, type SpanExporter } from "@opentelemetry/sdk-trace-base";
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
  if (options.spanExporter) {
    const testProvider = new BasicTracerProvider({
      resource: new Resource({ [ATTR_SERVICE_NAME]: options.serviceName ?? process.env.OTEL_SERVICE_NAME ?? "train-ticket-service" }),
      spanProcessors: [new SimpleSpanProcessor(options.spanExporter)],
    });
    trace.setGlobalTracerProvider(testProvider);
    return undefined;
  }
  const serviceName = options.serviceName ?? process.env.OTEL_SERVICE_NAME ?? "train-ticket-service";
  const traceExporter = options.spanExporter ?? new OTLPTraceExporter();
  sdk = new NodeSDK({
    resource: new Resource({ [ATTR_SERVICE_NAME]: serviceName }),
    traceExporter,
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
  return tracer.startSpan(`${attributes.consumerGroup} process ${attributes.eventType}`, spanOptions, context.active());
}

export type MessagingConsumerSpanAttributes = Readonly<{
  stream: string;
  consumerGroup: string;
  eventId: string;
  eventType: string;
  correlationId: string;
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
