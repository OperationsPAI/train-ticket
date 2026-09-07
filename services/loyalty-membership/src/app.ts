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
  livenessProbe,
  requestContext as kitRequestContext,
  requestFingerprint,
  sendError,
  type ErrorEnvelope,
  type IdempotencyStore,
  type RequestContext,
} from "@trainticket/ts-kit";
import { serviceProfile } from "./profile.js";
import type { EventConsumptionState } from "./subscriber-retry.js";

export type HealthStatus = Readonly<{
  status: "ok";
  service: typeof serviceProfile;
}>;

export type ProbeStatus = Readonly<{
  status: "ok" | "not_ready" | "unhealthy";
  probe: "live" | "ready";
  /**
   * Present only when bootstrap supplied an event-consumption reporter, so the
   * default `/readyz` body shape that existing tests and clients assert is
   * unchanged. See `readyBody` for why this is reported rather than gated on.
   */
  eventConsumption?: EventConsumptionState;
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
  /**
   * Reports whether the stream consumer is live or still retrying subscribe.
   * Surfaced on `/readyz` for observability; see `readyBody`.
   */
  eventConsumption?: () => EventConsumptionState;
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
  app.get("/healthz", async (_request, reply) => liveBody(reply));
  app.get("/metadata", async () => metadata());
  app.get("/live", async (_request, reply) => liveBody(reply));
  app.get("/livez", async (_request, reply) => liveBody(reply));
  app.get("/ready", async (_request, reply) => readyBody(reply, appOptions.storage, appOptions.eventConsumption));
  app.get("/readyz", async (_request, reply) => readyBody(reply, appOptions.storage, appOptions.eventConsumption));

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
  if ("repository" in options || "publisher" in options || "idempotencyStore" in options || "storage" in options || "instrumentation" in options || "eventConsumption" in options) {
    return options as AppOptions;
  }
  return { instrumentation: options as InstrumentationHooks };
}

function healthBody(): HealthStatus {
  return { status: health(), service: serviceProfile };
}

/**
 * Readiness.
 *
 * DECISION: while the subscriber is retrying subscribe, `/readyz` stays 200 and
 * reports `eventConsumption: "retrying"` in its body. It does NOT go 503.
 *
 * The tempting reading is "a service that consumes no events is not fully
 * functional, and readiness means do-not-send-me-traffic". But readiness in
 * this deployment means specifically "can this pod serve HTTP requests", and
 * flipping it here would make things worse, not better:
 *
 *  - `deploy/k8s/services.yaml` gives loyalty-membership `replicas: 1` behind a
 *    ClusterIP Service. Failing readiness removes the ONLY endpoint, so the
 *    synchronous HTTP API (enroll, redeem, member reads) that still works
 *    perfectly starts returning connection errors. A Redis outage would then
 *    take down the HTTP API too -- widening a partial outage into a total one,
 *    which is the same failure mode as the 2026-09-06 incident rather than a
 *    fix for it.
 *  - Readiness is the one probe that is NOT self-healing. It has no grace
 *    period and no restart: it just removes endpoints and waits. Since the
 *    background retry already recovers consumption without a pod restart,
 *    trading away HTTP availability buys nothing.
 *  - `deploy/e2e/12-restart.sh` waits on `kubectl rollout status` for every
 *    deployment, and a Deployment whose only pod is never Ready never becomes
 *    Available. A boot-time Redis blip would therefore hang the whole e2e
 *    rollout gate rather than surface one degraded consumer.
 *  - Nothing polls this endpoint except the kubelet (no ingress, no gateway, no
 *    service-to-service readiness pre-check), so 503 would carry no signal to
 *    any consumer -- it would only delete endpoints.
 *
 * The honest signal for "consumption is dead" is liveness, and that path is
 * already correct: once subscribe succeeds, ts-kit registers
 * `redis-consumer.loyalty-membership` and a later wedge fails `/healthz` after
 * the grace period. During the retry window the never-healthy guard keeps
 * liveness green on purpose, so the state is reported in the readiness body
 * for operators and dashboards instead of being encoded as an endpoint
 * withdrawal.
 *
 * Postgres readiness keeps its existing 503 behaviour: that one genuinely does
 * break the HTTP API, so removing the endpoint is correct there.
 */
async function readyBody(reply: FastifyReply, storage?: AppStorage, eventConsumption?: () => EventConsumptionState): Promise<ProbeStatus> {
  const consumption = eventConsumption?.();
  if (storage && !(await storage.ready())) {
    reply.status(503);
    return consumption ? { status: "not_ready", probe: "ready", eventConsumption: consumption } : { status: "not_ready", probe: "ready" };
  }
  return consumption ? { status: "ok", probe: "ready", eventConsumption: consumption } : probeBody("ready");
}

function probeBody(probe: "live" | "ready"): ProbeStatus {
  return { status: "ok", probe };
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
function liveBody(reply: FastifyReply): ProbeStatus {
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
