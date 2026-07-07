import Fastify, { type FastifyInstance, type FastifyRequest } from "fastify";

import {
  errorMessage,
  isFrameworkValidationError,
  requestContext as kitRequestContext,
  sendError,
  type ErrorEnvelope,
  type RequestContext,
} from "@trainticket/ts-kit";

import { serviceProfile } from "./profile.js";

export type HealthStatus = Readonly<{
  status: "ok";
  service: typeof serviceProfile;
}>;

export type ProbeStatus = Readonly<{
  status: "ok" | "not_ready";
  probe: "live" | "ready";
}>;

export type ServiceMetadata = Readonly<{
  service: typeof serviceProfile;
  observability: Readonly<{
    tracing: "opt-in";
    default: "noop";
  }>;
}>;

export type { ErrorEnvelope, RequestContext };

export type RequestTraceContext = RequestContext &
  Readonly<{
    method: string;
    url: string;
  }>;

export type TraceResult = RequestTraceContext &
  Readonly<{
    statusCode: number;
  }>;

export type TraceSpan = Readonly<{
  end?: (result: TraceResult) => void | Promise<void>;
}>;

export type InstrumentationHooks = Readonly<{
  onRequest?: (context: RequestContext) => void | Promise<void>;
  startSpan?: (context: RequestTraceContext) => void | TraceSpan | Promise<void | TraceSpan>;
}>;

type OTelSpan = Readonly<{
  setAttribute?: (key: string, value: string | number) => void;
  setAttributes?: (attributes: Record<string, string | number>) => void;
  end?: () => void;
}>;

type OTelTracer = Readonly<{
  startSpan: (name: string, options?: Record<string, unknown>) => OTelSpan;
}>;

export { InMemoryIdempotencyStore, handleIdempotentPost } from "@trainticket/ts-kit";

export function opentelemetryInstrumentationFromEnv(tracer?: OTelTracer): InstrumentationHooks {
  const exporter = process.env.OTEL_TRACES_EXPORTER?.trim().toLowerCase();
  if (!exporter || exporter === "none" || tracer === undefined) {
    return {};
  }
  return {
    startSpan: (context) => {
      const path = context.url.split("?")[0] || context.url;
      const attributes: Record<string, string | number> = {
        "service.name": serviceProfile.serviceId,
        "http.request.method": context.method,
        "http.method": context.method,
        "url.path": path,
        "http.route": path,
        "http.request_id": context.requestId,
        "http.correlation_id": context.correlationId,
      };
      const span = tracer.startSpan(`${context.method} ${path}`, { attributes, kind: "server" });
      span.setAttributes?.(attributes);
      return {
        end: (result) => {
          span.setAttribute?.("http.response.status_code", result.statusCode);
          span.setAttribute?.("http.status_code", result.statusCode);
          span.end?.();
        },
      };
    },
  };
}

export function health(): "ok" {
  return "ok";
}

export function metadata(): ServiceMetadata {
  return {
    service: serviceProfile,
    observability: {
      tracing: "opt-in",
      default: "noop",
    },
  };
}

export type AppStorage = Readonly<{
  ready: () => boolean | Promise<boolean>;
}>;

export function createApp(instrumentation: InstrumentationHooks = {}, storage?: AppStorage): FastifyInstance {
  const app: FastifyInstance = Fastify({ logger: false });
  const spans = new WeakMap<FastifyRequest, TraceSpan>();

  app.addHook("onRequest", async (request, reply) => {
    const context = requestContext(request);
    reply.header("x-request-id", context.requestId);
    reply.header("x-correlation-id", context.correlationId);
    await instrumentation.onRequest?.(context);

    const span = await instrumentation.startSpan?.(traceContext(request, context));
    if (span) {
      spans.set(request, span);
    }
  });

  app.addHook("onResponse", async (request, reply) => {
    const span = spans.get(request);
    spans.delete(request);
    await span?.end?.({ ...traceContext(request), statusCode: reply.statusCode });
  });

  app.get("/health", async () => healthBody());
  app.get("/healthz", async () => probeBody("live"));
  app.get("/metadata", async () => metadata());

  app.get("/live", async () => probeBody("live"));
  app.get("/livez", async () => probeBody("live"));
  app.get("/ready", async (_request, reply) => readyBody(reply, storage));
  app.get("/readyz", async (_request, reply) => readyBody(reply, storage));


  app.setNotFoundHandler((request, reply) => {
    sendError(reply, 404, "NOT_FOUND", `Route ${request.method} ${request.url} was not found`, requestContext(request));
  });

  app.setErrorHandler((error, request, reply) => {
    if (isFrameworkValidationError(error)) {
      sendError(reply, 400, "VALIDATION_FAILED", errorMessage(error), requestContext(request));
      return;
    }
    sendError(reply, 500, "INTERNAL_ERROR", errorMessage(error), requestContext(request));
  });

  return app;
}

function healthBody(): HealthStatus {
  return { status: health(), service: serviceProfile };
}

function probeBody(probe: ProbeStatus["probe"]): ProbeStatus {
  return { status: health(), probe };
}

async function readyBody(reply: { status: (statusCode: number) => unknown }, storage: AppStorage | undefined): Promise<ProbeStatus> {
  if (storage && !await storage.ready()) {
    reply.status(503);
    return { status: "not_ready", probe: "ready" };
  }
  return probeBody("ready");
}

function traceContext(request: AppRequest, context: RequestContext = requestContext(request)): RequestTraceContext {
  return {
    ...context,
    method: request.method,
    url: request.url,
  };
}

function requestContext(request: AppRequest): RequestContext {
  return kitRequestContext(request);
}

type AppRequest = FastifyRequest;
