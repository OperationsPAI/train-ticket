import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";

import {
  AccountApplicationService,
  ApplicationError,
  InMemoryAccountRepository,
  InMemoryEventPublisher,
  commandId,
  mapError,
  type AccountRepository,
  type DomainErrorMapping,
} from "./application.js";
import { type EventPublisher } from "./ports.js";
import {
  InMemoryIdempotencyStore,
  errorMessage,
  handleIdempotency,
  headerValue,
  livenessProbe,
  requestContext as kitRequestContext,
  requestFingerprint,
  sendError,
  type ErrorEnvelope,
  type IdempotencyStore,
  type RequestContext,
  type SchemaDetail,
} from "@trainticket/ts-kit";
import { serviceProfile } from "./profile.js";

export type HealthStatus = Readonly<{
  status: "ok";
  service: typeof serviceProfile;
}>;

export type ProbeStatus = Readonly<{
  status: "ok" | "not_ready" | "unhealthy";
  probe: "live" | "ready";
  /**
   * Present only when storage reports migration state AND migrations have not
   * applied yet, so the default probe body shape that existing tests and
   * clients assert is unchanged on the happy path. See `readyBody`.
   */
  schema?: SchemaDetail;
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

export type AppOptions = Readonly<{
  instrumentation?: InstrumentationHooks;
  repository?: AccountRepository;
  publisher?: EventPublisher;
  idempotencyStore?: IdempotencyStore;
  storage?: AppStorage;
}>;

export type AppStorage = Readonly<{
  ready: () => boolean | Promise<boolean>;
  /** Optional migration retry state; reported on `/readyz` while migrations are still being applied. */
  schema?: () => SchemaDetail;
  runCommand?: <T>(operation: (application: AccountApplicationService) => Promise<T>) => Promise<T>;
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
  const runWithService = appOptions.storage?.runCommand ?? (<T>(operation: (application: AccountApplicationService) => Promise<T>) => operation(accountService));
  const app: FastifyInstance = Fastify({ logger: false });
  const spans = new WeakMap<FastifyRequest, TraceSpan>();

  app.addHook("onRequest", async (request, reply) => {
    const context = resolveRequestContext(request);
    request.ctx = context;
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
  app.get("/healthz", async (_request, reply) => liveBody(reply));
  app.get("/metadata", async () => metadata());

  app.get("/live", async (_request, reply) => liveBody(reply));
  app.get("/livez", async (_request, reply) => liveBody(reply));
  app.get("/ready", async (_request, reply) => readyBody(reply, appOptions.storage));
  app.get("/readyz", async (_request, reply) => readyBody(reply, appOptions.storage));

  app.post("/api/v1/accounts", async (request, reply) => {
    await runIdempotentOperation(request, reply, idempotencyStore, 201, async (context, causationId) => {
      const body = objectBody(request.body);
      assertOptionalString(body.accountId, "accountId");
      return runWithService((service) => service.createAccount({ accountId: body.accountId as string | undefined, correlationId: context.correlationId, causationId }));
    });
  });

  app.get("/api/v1/accounts/:accountId", async (request, reply) => {
    try {
      return await runWithService((service) => service.getAccount(accountIdParam(request)));
    } catch (error) {
      sendApplicationError(reply, mapError(error), requestContext(request));
    }
  });

  app.post("/api/v1/accounts/:accountId/freeze", async (request, reply) => {
    await runIdempotentOperation(request, reply, idempotencyStore, 200, { preconditionDomainCodes: [] }, async (context, causationId) => {
      const body = objectBody(request.body);
      const reason = requiredString(body.reason, "reason");
      const operator = requiredString(body.operator, "operator");
      assertOptionalString(body.caseRef, "caseRef");
      return runWithService((service) => service.freezeAccount({ accountId: accountIdParam(request), reason, operator, caseRef: body.caseRef as string | undefined, correlationId: context.correlationId, causationId }));
    });
  });

  app.post("/api/v1/accounts/:accountId/unfreeze", async (request, reply) => {
    await runIdempotentOperation(request, reply, idempotencyStore, 200, { preconditionDomainCodes: "all" }, async (context, causationId) => {
      const body = objectBody(request.body);
      const reason = requiredString(body.reason, "reason");
      return runWithService((service) => service.unfreezeAccount({ accountId: accountIdParam(request), reason, correlationId: context.correlationId, causationId }));
    });
  });

  app.patch("/api/v1/accounts/:accountId/preferences", async (request, reply) => {
    await runIdempotentOperation(request, reply, idempotencyStore, 200, async (context, causationId) => {
      const body = objectBody(request.body);
      const preferenceKey = requiredString(body.preferenceKey, "preferenceKey");
      const value = requiredString(body.value, "value");
      return runWithService((service) => service.updatePreference({ accountId: accountIdParam(request), preferenceKey, value, correlationId: context.correlationId, causationId }));
    });
  });

  app.post("/api/v1/accounts/:accountId/start-closure", async (request, reply) => {
    await runIdempotentOperation(request, reply, idempotencyStore, 200, { preconditionDomainCodes: "all" }, async (context, causationId) =>
      runWithService((service) => service.startClosure({ accountId: accountIdParam(request), correlationId: context.correlationId, causationId })),
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

async function runIdempotentOperation(
  request: FastifyRequest,
  reply: FastifyReply,
  store: IdempotencyStore,
  successStatus: number,
  mappingOrHandler: DomainErrorMapping | ((context: RequestContext, causationId: string) => Promise<unknown>),
  maybeHandler?: (context: RequestContext, causationId: string) => Promise<unknown>,
): Promise<void> {
  const mapping = typeof mappingOrHandler === "function" ? {} : mappingOrHandler;
  const handler = typeof mappingOrHandler === "function" ? mappingOrHandler : maybeHandler;
  if (!handler) {
    throw new ApplicationError("UNAVAILABLE", "Idempotent handler was not configured", 503);
  }
  const context = requestContext(request);
  await handleIdempotency({
    key: headerValue(request.headers["idempotency-key"]),
    store,
    fingerprint: requestFingerprint(request.method, request.url.split("?")[0] ?? request.url, request.body ?? {}),
    context,
    reply,
    operation: async () => {
      try {
        return { statusCode: successStatus, body: await handler(context, commandId()) };
      } catch (error) {
        throw mapError(error, mapping);
      }
    },
  });
}

function normalizeOptions(options: AppOptions | InstrumentationHooks): AppOptions {
  if ("repository" in options || "publisher" in options || "idempotencyStore" in options || "instrumentation" in options || "storage" in options) {
    return options as AppOptions;
  }
  return { instrumentation: options as InstrumentationHooks };
}

function healthBody(): HealthStatus {
  return { status: health(), service: serviceProfile };
}

function probeBody(probe: ProbeStatus["probe"]): ProbeStatus {
  return { status: health(), probe };
}

/**
 * Liveness.
 *
 * A liveness probe that can never fail is what turned a 10-second Redis
 * restart into a 20-hour outage: readiness pulled the pod out of the Service
 * while liveness kept saying 200, so kubelet never restarted the wedged
 * process.
 *
 * This is deliberately NOT a dependency check. `livenessProbe()` reports dead
 * only once a registered component (Redis connection, stream consumer loop,
 * outbox relay) has been *continuously* unhealthy past its grace period
 * (REDIS_LIVENESS_GRACE_MS, default 5 minutes). A transient Redis blip
 * reconnects in seconds and never trips it; a permanently wedged process
 * gets restarted.
 */
function liveBody(reply: { status: (statusCode: number) => unknown }): ProbeStatus {
  const probe = livenessProbe();
  if (!probe.live) {
    console.error({
      service: serviceProfile.serviceId,
      message: "liveness probe failing; process is wedged and only a restart can recover it",
      failed: probe.failed.map((component) => ({
        component: component.name,
        reason: component.reason,
        unhealthyForMs: component.unhealthyForMs,
        gracePeriodMs: component.gracePeriodMs,
      })),
    });
    reply.status(503);
    return { status: "unhealthy", probe: "live" };
  }
  return probeBody("live");
}

/**
 * Readiness.
 *
 * While database migrations are still being retried this stays 503 and the body
 * says why (`schema: { schema: "migrating", attempts, retryingForMs, lastError }`).
 * That is the OPPOSITE of the decision taken for a retrying event subscriber,
 * which keeps `/readyz` at 200, and deliberately so: a dead consumer still
 * leaves a fully working HTTP API, whereas a missing schema means every request
 * hits a table that does not exist. Serving reads against a missing schema is
 * worse than serving them with a dead consumer -- an honest 503 beats a 500
 * that a caller may treat as terminal. See `superviseMigrations` in ts-kit for
 * the full readiness/liveness reasoning.
 *
 * The change from the pre-fix behaviour is not the 503 itself -- that was
 * already the gate -- but that it is now TEMPORARY: the background retry clears
 * it when the database returns, instead of the pod needing a manual
 * `kubectl rollout restart`.
 */
async function readyBody(reply: { status: (statusCode: number) => unknown }, storage: AppStorage | undefined): Promise<ProbeStatus> {
  if (storage && !await storage.ready()) {
    reply.status(503);
    const schema = storage.schema?.();
    return schema && schema.schema !== "applied"
      ? { status: "not_ready", probe: "ready", schema }
      : { status: "not_ready", probe: "ready" };
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
  if (request.ctx) {
    return request.ctx;
  }
  const context = resolveRequestContext(request);
  request.ctx = context;
  return context;
}

function resolveRequestContext(request: AppRequest): RequestContext {
  return kitRequestContext(request);
}

type AppRequest = FastifyRequest & { ctx?: RequestContext };
type AppReply = FastifyReply;

function sendApplicationError(reply: AppReply, error: ApplicationError, context: RequestContext): void {
  sendError(reply, error.statusCode, error.code, errorMessage(error), context, error.details);
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
