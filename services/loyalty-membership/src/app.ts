import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";

import {
  ApplicationError,
  InMemoryEventPublisher,
  InMemoryMemberRepository,
  LoyaltyMembershipApplicationService,
  mapError,
  type MemberRepository,
} from "./application.js";
import { type EventPublisher } from "./ports.js";
import {
  InMemoryIdempotencyStore,
  errorMessage,
  handleIdempotency,
  headerValue,
  requestContext as kitRequestContext,
  requestFingerprint,
  sendError,
  type ErrorEnvelope,
  type IdempotencyStore,
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

export type RequestTraceContext = RequestContext & Readonly<{ method: string; url: string }>;
export type TraceResult = RequestTraceContext & Readonly<{ statusCode: number }>;
export type TraceSpan = Readonly<{ end?: (result: TraceResult) => void | Promise<void> }>;
export type InstrumentationHooks = Readonly<{
  onRequest?: (context: RequestContext) => void | Promise<void>;
  startSpan?: (context: RequestTraceContext) => void | TraceSpan | Promise<void | TraceSpan>;
}>;

export type AppOptions = Readonly<{
  instrumentation?: InstrumentationHooks;
  repository?: MemberRepository;
  publisher?: EventPublisher;
  idempotencyStore?: IdempotencyStore;
  storage?: AppStorage;
}>;

export type AppStorage = Readonly<{
  ready: () => boolean | Promise<boolean>;
  runCommand?: <T>(operation: (application: LoyaltyMembershipApplicationService) => Promise<T>) => Promise<T>;
}>;

type OTelSpan = Readonly<{
  setAttribute?: (key: string, value: string | number) => void;
  setAttributes?: (attributes: Record<string, string | number>) => void;
  end?: () => void;
}>;

type OTelTracer = Readonly<{ startSpan: (name: string, options?: Record<string, unknown>) => OTelSpan }>;

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
  return { service: serviceProfile, observability: { tracing: "opt-in", default: "noop" } };
}

export function createApp(options: AppOptions | InstrumentationHooks = {}): FastifyInstance {
  const appOptions = normalizeOptions(options);
  const instrumentation = appOptions.instrumentation ?? {};
  const repository = appOptions.repository ?? new InMemoryMemberRepository();
  const publisher = appOptions.publisher ?? new InMemoryEventPublisher();
  const idempotencyStore = appOptions.idempotencyStore ?? new InMemoryIdempotencyStore();
  const service = new LoyaltyMembershipApplicationService(repository, publisher);
  const runWithService = appOptions.storage?.runCommand ?? (<T>(operation: (application: LoyaltyMembershipApplicationService) => Promise<T>) => operation(service));
  const app: FastifyInstance = Fastify({ logger: false });
  const spans = new WeakMap<FastifyRequest, TraceSpan>();

  app.addHook("onRequest", async (request, reply) => {
    const context = requestContext(request);
    request.ctx = context;
    reply.header("x-request-id", context.requestId);
    reply.header("x-correlation-id", context.correlationId);
    await instrumentation.onRequest?.(context);
    const span = await instrumentation.startSpan?.(traceContext(request, context));
    if (span) spans.set(request, span);
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
  app.get("/ready", async (_request, reply) => readyBody(reply, appOptions.storage));
  app.get("/readyz", async (_request, reply) => readyBody(reply, appOptions.storage));

  app.post("/members/enroll", async (request, reply) => {
    try {
      const body = objectBody(request.body);
      const accountId = requiredString(body.accountId, "accountId");
      const result = await runWithService((application) => application.enrollMember(accountId));
      reply.status(result.created ? 201 : 200);
      return result.member;
    } catch (error) {
      sendApplicationError(reply, mapError(error), requestContext(request));
    }
  });

  app.get("/members/by-account/:accountId", async (request, reply) => {
    try {
      const params = request.params as { accountId?: unknown };
      if (typeof params.accountId !== "string" || params.accountId.trim().length === 0) {
        throw new ApplicationError("VALIDATION_FAILED", "accountId is required", 400, { field: "accountId" });
      }
      return await runWithService((application) => application.getMemberByAccountId(params.accountId as string));
    } catch (error) {
      sendApplicationError(reply, mapError(error), requestContext(request));
    }
  });

  app.get("/members/:id", async (request, reply) => {
    try {
      return await runWithService((application) => application.getMember(memberIdParam(request)));
    } catch (error) {
      sendApplicationError(reply, mapError(error), requestContext(request));
    }
  });

  app.post("/members/:id/redeem", async (request, reply) => {
    await handleIdempotency({
      key: headerValue(request.headers["idempotency-key"]),
      store: idempotencyStore,
      fingerprint: requestFingerprint(request.method, request.url.split("?")[0] ?? request.url, request.body ?? null),
      context: requestContext(request),
      reply,
      operation: async () => {
        const body = objectBody(request.body);
        const points = requiredInteger(body.points, "points");
        assertOptionalString(body.redemptionId, "redemptionId");
        assertOptionalString(body.reasonCode, "reasonCode");
        const result = await runWithService((application) => application.redeemPoints({
          memberId: memberIdParam(request),
          points,
          redemptionId: body.redemptionId as string | undefined,
          reasonCode: body.reasonCode as string | undefined,
          correlationId: requestContext(request).correlationId,
          causationId: headerValue(request.headers["idempotency-key"]),
        }));
        return { statusCode: 200, body: result };
      },
    });
  });


  app.post("/members/:id/redeem-ticket", async (request, reply) => {
    await handleIdempotency({
      key: headerValue(request.headers["idempotency-key"]),
      store: idempotencyStore,
      fingerprint: requestFingerprint(request.method, request.url.split("?")[0] ?? request.url, request.body ?? null),
      context: requestContext(request),
      reply,
      operation: async () => {
        const body = objectBody(request.body);
        const result = await runWithService((application) => application.redeemTicketPoints({
          memberId: memberIdParam(request),
          orderId: requiredString(body.orderId, "orderId"),
          pointsToRedeem: requiredInteger(body.pointsToRedeem, "pointsToRedeem"),
          fareAmountMinor: requiredNonNegativeInteger(body.fareAmountMinor, "fareAmountMinor"),
          redemptionId: optionalString(body.redemptionId, "redemptionId"),
          correlationId: requestContext(request).correlationId,
          causationId: headerValue(request.headers["idempotency-key"]),
        }));
        return { statusCode: 200, body: result };
      },
    });
  });

  app.post("/members/:id/expire-points", async (request, reply) => {
    try {
      const result = await runWithService((application) => application.expirePoints({
        memberId: memberIdParam(request),
        correlationId: requestContext(request).correlationId,
      }));
      return result;
    } catch (error) {
      sendApplicationError(reply, mapError(error), requestContext(request));
    }
  });

  app.post("/members/:id/evaluate-tier", async (request, reply) => {
    try {
      const body = objectBody(request.body);
      const result = await runWithService((application) => application.evaluateTier({
        memberId: memberIdParam(request),
        evaluationYear: requiredNonNegativeInteger(body.evaluationYear, "evaluationYear"),
        correlationId: requestContext(request).correlationId,
      }));
      return result;
    } catch (error) {
      sendApplicationError(reply, mapError(error), requestContext(request));
    }
  });

  app.setNotFoundHandler((request, reply) => {
    sendError(reply, 404, "NOT_FOUND", `Route ${request.method} ${request.url} was not found`, requestContext(request));
  });

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof ApplicationError) {
      sendApplicationError(reply, error, requestContext(request));
      return;
    }
    const mapped = mapError(error);
    if (mapped.code !== "UNAVAILABLE") {
      sendApplicationError(reply, mapped, requestContext(request));
      return;
    }
    sendError(reply, 500, "INTERNAL_ERROR", errorMessage(error), requestContext(request));
  });

  return app;
}

function normalizeOptions(options: AppOptions | InstrumentationHooks): AppOptions {
  if ("repository" in options || "publisher" in options || "idempotencyStore" in options || "storage" in options || "instrumentation" in options) {
    return options as AppOptions;
  }
  return { instrumentation: options as InstrumentationHooks };
}

function healthBody(): HealthStatus {
  return { status: health(), service: serviceProfile };
}

async function readyBody(reply: FastifyReply, storage?: AppStorage): Promise<ProbeStatus> {
  if (storage && !(await storage.ready())) {
    reply.status(503);
    return { status: "not_ready", probe: "ready" };
  }
  return probeBody("ready");
}

function probeBody(probe: "live" | "ready"): ProbeStatus {
  return { status: "ok", probe };
}

function memberIdParam(request: FastifyRequest): string {
  const params = request.params as { id?: unknown };
  if (typeof params.id !== "string" || params.id.trim().length === 0) {
    throw new ApplicationError("VALIDATION_FAILED", "Member id is required", 400, { field: "id" });
  }
  return params.id;
}

function objectBody(body: unknown): Record<string, unknown> {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw new ApplicationError("VALIDATION_FAILED", "Request body must be an object", 400);
  }
  return body as Record<string, unknown>;
}

function requiredString(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new ApplicationError("VALIDATION_FAILED", `${field} is required`, 400, { field });
  }
  return value;
}

function requiredInteger(value: unknown, field: string): number {
  if (!Number.isInteger(value) || (value as number) <= 0) {
    throw new ApplicationError("VALIDATION_FAILED", `${field} must be a positive integer`, 400, { field });
  }
  return value as number;
}

function assertOptionalString(value: unknown, field: string): void {
  optionalString(value, field);
}

function optionalString(value: unknown, field: string): string | undefined {
  if (value !== undefined && typeof value !== "string") {
    throw new ApplicationError("VALIDATION_FAILED", `${field} must be a string`, 400, { field });
  }
  return value as string | undefined;
}

function requiredNonNegativeInteger(value: unknown, field: string): number {
  if (!Number.isInteger(value) || (value as number) < 0) {
    throw new ApplicationError("VALIDATION_FAILED", `${field} must be a non-negative integer`, 400, { field });
  }
  return value as number;
}

function sendApplicationError(reply: FastifyReply, error: ApplicationError, context: RequestContext): void {
  sendError(reply, error.statusCode, error.code, error.message, context, error.details);
}

function requestContext(request: FastifyRequest): RequestContext {
  return request.ctx ?? kitRequestContext(request);
}

function traceContext(request: FastifyRequest, context = requestContext(request)): RequestTraceContext {
  return { ...context, method: request.method, url: request.url };
}
