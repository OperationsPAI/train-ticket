import { randomUUID } from "node:crypto";

import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";

import {
  AccountApplicationService,
  ApplicationError,
  InMemoryAccountRepository,
  InMemoryEventPublisher,
  InMemoryIdempotencyStore,
  commandId,
  fingerprintRequest,
  mapError,
  type AccountRepository,
  type IdempotencyRecord,
} from "./application.js";
import { type EventPublisher } from "./ports.js";
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

export type AppOptions = Readonly<{
  instrumentation?: InstrumentationHooks;
  repository?: AccountRepository;
  publisher?: EventPublisher;
  idempotencyStore?: InMemoryIdempotencyStore;
}>;

type OTelSpan = Readonly<{
  setAttribute?: (key: string, value: string | number) => void;
  setAttributes?: (attributes: Record<string, string | number>) => void;
  end?: () => void;
}>;

type OTelTracer = Readonly<{
  startSpan: (name: string, options?: Record<string, unknown>) => OTelSpan;
}>;

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

export function createApp(options: AppOptions | InstrumentationHooks = {}): FastifyInstance {
  const appOptions = normalizeOptions(options);
  const instrumentation = appOptions.instrumentation ?? {};
  const repository = appOptions.repository ?? new InMemoryAccountRepository();
  const publisher = appOptions.publisher ?? new InMemoryEventPublisher();
  const idempotencyStore = appOptions.idempotencyStore ?? new InMemoryIdempotencyStore();
  const accountService = new AccountApplicationService(repository, publisher);
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

  app.post("/api/v1/accounts", async (request, reply) => {
    await withIdempotency(request, reply, idempotencyStore, 201, async (context, causationId) => {
      const body = objectBody(request.body);
      assertOptionalString(body.accountId, "accountId");
      return accountService.createAccount({ accountId: body.accountId as string | undefined, correlationId: context.correlationId, causationId });
    });
  });

  app.get("/api/v1/accounts/:accountId", async (request, reply) => {
    try {
      return await accountService.getAccount(accountIdParam(request));
    } catch (error) {
      sendApplicationError(reply, mapError(error), requestContext(request));
    }
  });

  app.post("/api/v1/accounts/:accountId/freeze", async (request, reply) => {
    await withIdempotency(request, reply, idempotencyStore, 200, async (context, causationId) => {
      const body = objectBody(request.body);
      const reason = requiredString(body.reason, "reason");
      const operator = requiredString(body.operator, "operator");
      assertOptionalString(body.caseRef, "caseRef");
      return accountService.freezeAccount({ accountId: accountIdParam(request), reason, operator, caseRef: body.caseRef as string | undefined, correlationId: context.correlationId, causationId });
    });
  });

  app.post("/api/v1/accounts/:accountId/unfreeze", async (request, reply) => {
    await withIdempotency(request, reply, idempotencyStore, 200, async (context, causationId) => {
      const body = objectBody(request.body);
      const reason = requiredString(body.reason, "reason");
      return accountService.unfreezeAccount({ accountId: accountIdParam(request), reason, correlationId: context.correlationId, causationId });
    });
  });

  app.patch("/api/v1/accounts/:accountId/preferences", async (request, reply) => {
    await withIdempotency(request, reply, idempotencyStore, 200, async (context, causationId) => {
      const body = objectBody(request.body);
      const preferenceKey = requiredString(body.preferenceKey, "preferenceKey");
      const value = requiredString(body.value, "value");
      return accountService.updatePreference({ accountId: accountIdParam(request), preferenceKey, value, correlationId: context.correlationId, causationId });
    });
  });

  app.post("/api/v1/accounts/:accountId/start-closure", async (request, reply) => {
    await withIdempotency(request, reply, idempotencyStore, 200, async (context, causationId) =>
      accountService.startClosure({ accountId: accountIdParam(request), correlationId: context.correlationId, causationId }),
    );
  });

  app.setNotFoundHandler((request, reply) => {
    sendError(reply, 404, "NOT_FOUND", `Route ${request.method} ${request.url} was not found`, requestContext(request));
  });

  app.setErrorHandler((error, request, reply) => {
    sendApplicationError(reply, mapError(error), requestContext(request));
  });

  return app;
}

async function withIdempotency(
  request: FastifyRequest,
  reply: FastifyReply,
  store: InMemoryIdempotencyStore,
  successStatus: number,
  handler: (context: RequestContext, causationId: string) => Promise<unknown>,
): Promise<void> {
  const context = requestContext(request);
  const key = headerValue(request.headers["idempotency-key"]);
  if (!key) {
    sendError(reply, 400, "VALIDATION_FAILED", "Idempotency-Key header is required", context, { field: "Idempotency-Key" });
    return;
  }

  const fingerprint = fingerprintRequest(request.method, request.url.split("?")[0] ?? request.url, request.body ?? {});
  const existing = store.get(key);
  if (existing) {
    if (existing.fingerprint !== fingerprint) {
      sendError(reply, 422, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was reused with a different request", context);
      return;
    }
    reply.status(existing.statusCode).send(existing.body);
    return;
  }

  try {
    const body = await handler(context, commandId());
    const record: IdempotencyRecord = { fingerprint, statusCode: successStatus, body };
    store.set(key, record);
    reply.status(successStatus).send(body);
  } catch (error) {
    sendApplicationError(reply, mapError(error), context);
  }
}

function normalizeOptions(options: AppOptions | InstrumentationHooks): AppOptions {
  if ("repository" in options || "publisher" in options || "idempotencyStore" in options || "instrumentation" in options) {
    return options as AppOptions;
  }
  return { instrumentation: options as InstrumentationHooks };
}

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message.length > 0 ? error.message : "Internal server error";
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
  const correlationId = headerValue(request.headers["x-correlation-id"]) ?? `corr-${randomUUID()}`;
  return { requestId, correlationId };
}

type AppRequest = FastifyRequest;
type AppReply = FastifyReply;

function headerValue(value: string | string[] | undefined): string | undefined {
  const candidate = Array.isArray(value) ? value[0] : value;
  return candidate && candidate.trim().length > 0 ? candidate : undefined;
}

function sendApplicationError(reply: AppReply, error: ApplicationError, context: RequestContext): void {
  sendError(reply, error.statusCode, error.code, errorMessage(error), context, error.details);
}

function sendError(reply: AppReply, statusCode: number, code: string, message: string, context: RequestContext, details: Record<string, unknown> = {}): void {
  const envelope: ErrorEnvelope = {
    code,
    message,
    correlationId: context.correlationId,
    details,
  };
  reply.status(statusCode).send(envelope);
}

function objectBody(body: unknown): Record<string, unknown> {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw new ApplicationError("VALIDATION_FAILED", "Request body must be a JSON object", 400);
  }
  return body as Record<string, unknown>;
}

function requiredString(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new ApplicationError("VALIDATION_FAILED", `${field} is required`, 400, { field });
  }
  return value;
}

function assertOptionalString(value: unknown, field: string): void {
  if (value !== undefined && typeof value !== "string") {
    throw new ApplicationError("VALIDATION_FAILED", `${field} must be a string`, 400, { field });
  }
}

function accountIdParam(request: FastifyRequest): string {
  return requiredString((request.params as { accountId?: unknown }).accountId, "accountId");
}
