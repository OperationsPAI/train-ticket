import { randomUUID, createHash } from "node:crypto";

import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";

import { serviceProfile } from "./profile.js";

export type HealthStatus = Readonly<{
  status: "ok";
  service: typeof serviceProfile;
}>;

export type ProbeStatus = Readonly<{
  status: "ok";
  probe: "live" | "ready";
}>;

export type ServiceMetadata = Readonly<{
  service: typeof serviceProfile;
  observability: Readonly<{
    tracing: "opt-in";
    default: "noop";
  }>;
}>;

export type ErrorEnvelope = Readonly<{
  code: string;
  message: string;
  correlationId: string;
  details: Record<string, unknown>;
}>;

export type RequestContext = Readonly<{
  requestId: string;
  correlationId: string;
}>;

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

type IdempotencyRecord = Readonly<{
  bodyHash: string;
  statusCode: number;
  payload: unknown;
}>;

export class InMemoryIdempotencyStore {
  private readonly records = new Map<string, IdempotencyRecord>();

  get(key: string): IdempotencyRecord | undefined {
    return this.records.get(key);
  }

  save(key: string, record: IdempotencyRecord): void {
    this.records.set(key, record);
  }
}

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

export function createApp(instrumentation: InstrumentationHooks = {}): FastifyInstance {
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
  app.get("/ready", async () => probeBody("ready"));
  app.get("/readyz", async () => probeBody("ready"));

  app.setNotFoundHandler((request, reply) => {
    sendError(reply, 404, "NOT_FOUND", `Route ${request.method} ${request.url} was not found`, requestContext(request));
  });

  app.setErrorHandler((error, request, reply) => {
    if (isValidationError(error)) {
      sendError(reply, 400, "VALIDATION_FAILED", errorMessage(error), requestContext(request));
      return;
    }
    sendError(reply, 500, "INTERNAL_ERROR", errorMessage(error), requestContext(request));
  });

  return app;
}

export async function handleIdempotentPost<T>(
  request: FastifyRequest,
  reply: FastifyReply,
  store: InMemoryIdempotencyStore,
  handler: () => Promise<Readonly<{ statusCode: number; payload: T }>>,
): Promise<T | ErrorEnvelope> {
  const context = requestContext(request);
  const key = headerValue(request.headers["idempotency-key"]);
  if (key === undefined) {
    reply.status(400);
    return errorBody("VALIDATION_FAILED", "Idempotency-Key header is required", context);
  }

  const bodyHash = hashBody(request.body);
  const existing = store.get(key);
  if (existing !== undefined) {
    if (existing.bodyHash !== bodyHash) {
      reply.status(422);
      return errorBody("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was reused with a different request body", context);
    }
    reply.status(existing.statusCode);
    return existing.payload as T;
  }

  const result = await handler();
  store.save(key, { bodyHash, statusCode: result.statusCode, payload: result.payload });
  reply.status(result.statusCode);
  return result.payload;
}

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message.length > 0 ? error.message : "Internal server error";
}

function isValidationError(error: unknown): error is Error & { statusCode?: number; validation?: unknown } {
  return typeof error === "object" && error !== null && (
    ("statusCode" in error && error.statusCode === 400) || "validation" in error
  );
}

function healthBody(): HealthStatus {
  return { status: health(), service: serviceProfile };
}

function probeBody(probe: ProbeStatus["probe"]): ProbeStatus {
  return { status: health(), probe };
}

function traceContext(request: AppRequest, context: RequestContext = requestContext(request)): RequestTraceContext {
  return {
    ...context,
    method: request.method,
    url: request.url,
  };
}

function requestContext(request: AppRequest): RequestContext {
  const requestId = headerValue(request.headers["x-request-id"]) ?? request.id ?? randomUUID();
  const correlationId = headerValue(request.headers["x-correlation-id"]) ?? requestId;
  return { requestId, correlationId };
}

type AppRequest = FastifyRequest;
type AppReply = FastifyReply;

function headerValue(value: string | string[] | undefined): string | undefined {
  const candidate = Array.isArray(value) ? value[0] : value;
  return candidate && candidate.trim().length > 0 ? candidate : undefined;
}

function sendError(reply: AppReply, statusCode: number, code: string, message: string, context: RequestContext): void {
  reply.status(statusCode).send(errorBody(code, message, context));
}

function errorBody(code: string, message: string, context: RequestContext): ErrorEnvelope {
  return {
    code,
    message,
    correlationId: context.correlationId,
    details: {},
  };
}

function hashBody(body: unknown): string {
  return createHash("sha256").update(JSON.stringify(body ?? null)).digest("hex");
}
