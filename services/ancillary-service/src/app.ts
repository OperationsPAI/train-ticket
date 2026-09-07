import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";

import { AncillaryApplicationService, InMemoryAncillaryRepository, isDomainError, type AncillaryRepository } from "./application.js";
import { serviceProfile } from "./profile.js";
import { HttpFarePricingGateway, type AncillaryPricingGateway } from "./pricing.js";
import { InMemoryIdempotencyStore, errorMessage, handleIdempotency, headerValue, livenessProbe, requestContext as kitRequestContext, requestFingerprint, sendError as kitSendError, type ErrorEnvelope, type EventPublisher, type IdempotencyStore, type RequestContext, type SchemaDetail } from "@trainticket/ts-kit";

export type HealthStatus = Readonly<{ status: "ok"; service: typeof serviceProfile }>;
/** `schema` is present only while migrations are still being retried, so the happy-path body shape is unchanged. See `readyBody`. */
export type ProbeStatus = Readonly<{ status: "ok" | "not_ready" | "unhealthy"; probe: "live" | "ready"; schema?: SchemaDetail }>;
export type ServiceMetadata = Readonly<{ service: typeof serviceProfile; observability: Readonly<{ tracing: "opt-in"; default: "noop" }> }>;
export type ErrorBody = ErrorEnvelope;
export type { RequestContext };
export type RequestTraceContext = RequestContext & Readonly<{ method: string; url: string }>;
export type TraceResult = RequestTraceContext & Readonly<{ statusCode: number }>;
export type TraceSpan = Readonly<{ end?: (result: TraceResult) => void | Promise<void> }>;
export type InstrumentationHooks = Readonly<{ onRequest?: (context: RequestContext) => void | Promise<void>; startSpan?: (context: RequestTraceContext) => void | TraceSpan | Promise<void | TraceSpan> }>;

type OTelSpan = Readonly<{ setAttribute?: (key: string, value: string | number) => void; setAttributes?: (attributes: Record<string, string | number>) => void; end?: () => void }>;
type OTelTracer = Readonly<{ startSpan: (name: string, options?: Record<string, unknown>) => OTelSpan }>;

type AppStorage = Readonly<{ ready: () => boolean | Promise<boolean>; /** Optional migration retry state; reported on `/readyz` while migrations are still being applied. */ schema?: () => SchemaDetail; runCommand?: <T>(operation: (service: AncillaryApplicationService) => Promise<T>) => Promise<T> }>;
type AppDependencies = Readonly<{ repository?: AncillaryRepository; publisher?: EventPublisher; pricingGateway?: AncillaryPricingGateway; idempotencyStore?: IdempotencyStore; storage?: AppStorage }>;

const defaultRepository = new InMemoryAncillaryRepository();
const defaultIdempotencyStore = new InMemoryIdempotencyStore();

export function resetAncillaryStore(): void { defaultRepository.clear(); defaultIdempotencyStore.clear(); }

export function opentelemetryInstrumentationFromEnv(tracer?: OTelTracer): InstrumentationHooks {
  const exporter = process.env.OTEL_TRACES_EXPORTER?.trim().toLowerCase();
  if (!exporter || exporter === "none" || tracer === undefined) return {};
  return { startSpan: (context) => {
    const path = context.url.split("?")[0] || context.url;
    const attributes = { "service.name": serviceProfile.serviceId, "http.request.method": context.method, "http.method": context.method, "url.path": path, "http.route": path, "http.request_id": context.requestId, "http.correlation_id": context.correlationId };
    const span = tracer.startSpan(`${context.method} ${path}`, { attributes, kind: "server" });
    span.setAttributes?.(attributes);
    return { end: (result) => { span.setAttribute?.("http.response.status_code", result.statusCode); span.setAttribute?.("http.status_code", result.statusCode); span.end?.(); } };
  } };
}
export function health(): "ok" { return "ok"; }
export function metadata(): ServiceMetadata { return { service: serviceProfile, observability: { tracing: "opt-in", default: "noop" } }; }

export function createApp(instrumentation: InstrumentationHooks = {}, dependencies: AppDependencies = {}): FastifyInstance {
  const app = Fastify({ logger: false });
  const pricingGateway = dependencies.pricingGateway ?? farePricingGatewayFromEnv();
  const service = new AncillaryApplicationService(dependencies.repository ?? defaultRepository, dependencies.publisher, pricingGateway);
  const idempotencyStore = dependencies.idempotencyStore ?? defaultIdempotencyStore;
  const runCommand = dependencies.storage?.runCommand ?? (<T>(operation: (svc: AncillaryApplicationService) => Promise<T>) => operation(service));
  const spans = new WeakMap<FastifyRequest, TraceSpan>();

  app.addHook("onRequest", async (request, reply) => {
    const context = requestContext(request);
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
  app.get("/healthz", async (_request, reply) => healthzBody(reply));
  app.get("/metadata", async () => metadata());
  app.get("/live", async (_request, reply) => liveBody(reply));
  app.get("/livez", async (_request, reply) => liveBody(reply));
  app.get("/ready", async (_request, reply) => readyBody(reply, dependencies.storage));
  app.get("/readyz", async (_request, reply) => readyBody(reply, dependencies.storage));

  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-catalog-items", async (request, ctx, reply) => ({ statusCode: 201, body: await runCommand((svc) => svc.createCatalogItem(request.body as any)) }), replyNoop);
  stateChanging(app, idempotencyStore, "PUT", "/api/v1/ancillary-catalog-items/:catalogItemId", async (request) => ({ statusCode: 200, body: await runCommand((svc) => svc.updateCatalogItem(param(request, "catalogItemId"), request.body as any)) }), replyNoop);
  stateChanging(app, idempotencyStore, "DELETE", "/api/v1/ancillary-catalog-items/:catalogItemId", async (request) => { await runCommand((svc) => svc.deleteDraftCatalogItem(param(request, "catalogItemId"))); return { statusCode: 204, body: null }; }, replyNoop);
  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-catalog-items/:catalogItemId/publish", async (request, ctx) => ({ statusCode: 200, body: await runCommand((svc) => svc.publishCatalogItem(param(request, "catalogItemId"), request.body as any, ctx.correlationId)) }), replyNoop);
  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-catalog-items/:catalogItemId/suspend", async (request, ctx) => ({ statusCode: 200, body: await runCommand((svc) => svc.suspendCatalogItem(param(request, "catalogItemId"), request.body as any, ctx.correlationId)) }), replyNoop);
  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-catalog-items/:catalogItemId/supersede", async (request, ctx) => ({ statusCode: 200, body: await runCommand((svc) => svc.supersedeCatalogItem(param(request, "catalogItemId"), request.body as any, ctx.correlationId)) }), replyNoop);
  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-catalog-items/:catalogItemId/expire", async (request) => ({ statusCode: 200, body: await runCommand((svc) => svc.expireCatalogItem(param(request, "catalogItemId"), request.body as any)) }), replyNoop);

  app.get("/api/v1/ancillary-catalog-items/:catalogItemId", async (request, reply) => handleRead(reply, request, () => runCommand((svc) => svc.getCatalogItem(param(request, "catalogItemId")))));
  app.get("/api/v1/ancillary-catalog-items", async (request, reply) => handleRead(reply, request, () => runCommand((svc) => svc.listCatalogItems({ ...query(request), limit: limit(request), offset: offset(request) }))));

  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-offers", async (request) => ({ statusCode: 201, body: await runCommand((svc) => svc.draftOffer(request.body as any)) }), replyNoop);
  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-offers/:ancillaryOfferId/quote", async (request, ctx) => ({ statusCode: 200, body: await runCommand((svc) => svc.quoteOffer(param(request, "ancillaryOfferId"), quoteInput(request), ctx.correlationId)) }), replyNoop);
  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-offers/:ancillaryOfferId/select", async (request, ctx) => ({ statusCode: 201, body: await runCommand((svc) => svc.selectOffer(param(request, "ancillaryOfferId"), request.body as any, ctx.correlationId)) }), replyNoop);
  app.get("/api/v1/ancillary-offers/:ancillaryOfferId", async (request, reply) => handleRead(reply, request, () => runCommand((svc) => svc.getOffer(param(request, "ancillaryOfferId")))));
  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-order-items/:ancillaryOrderItemId/confirm", async (request, ctx) => ({ statusCode: 200, body: await runCommand((svc) => svc.confirmOrderItem(param(request, "ancillaryOrderItemId"), request.body as any, ctx.correlationId)) }), replyNoop);
  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-order-items/:ancillaryOrderItemId/fulfillment-ready", async (request, ctx) => ({ statusCode: 200, body: await runCommand((svc) => svc.fulfillmentReady(param(request, "ancillaryOrderItemId"), request.body as any, ctx.correlationId)) }), replyNoop);
  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-order-items/:ancillaryOrderItemId/cancel", async (request, ctx) => ({ statusCode: 200, body: await runCommand((svc) => svc.cancelOrderItem(param(request, "ancillaryOrderItemId"), request.body as any, ctx.correlationId)) }), replyNoop);
  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-order-items/:ancillaryOrderItemId/refund-suggestions", async (request, ctx) => ({ statusCode: 200, body: await runCommand((svc) => svc.suggestRefund(param(request, "ancillaryOrderItemId"), request.body as any, ctx.correlationId)) }), replyNoop);
  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-order-items/:ancillaryOrderItemId/refunded", async (request, ctx) => ({ statusCode: 200, body: await runCommand((svc) => svc.refunded(param(request, "ancillaryOrderItemId"), request.body as any, ctx.correlationId)) }), replyNoop);
  stateChanging(app, idempotencyStore, "POST", "/api/v1/ancillary-order-items/:ancillaryOrderItemId/fulfillment-facts", async (request, ctx) => ({ statusCode: 200, body: await runCommand((svc) => svc.recordFulfillmentFact(param(request, "ancillaryOrderItemId"), request.body as any, ctx.correlationId)) }), replyNoop);
  app.get("/api/v1/ancillary-order-items/:ancillaryOrderItemId", async (request, reply) => handleRead(reply, request, () => runCommand((svc) => svc.getOrderItem(param(request, "ancillaryOrderItemId")))));
  app.get("/api/v1/ancillary-order-items", async (request, reply) => handleRead(reply, request, async () => {
    const q = query(request);
    if (!q.journeyOrderId) throw new ValidationError("journeyOrderId is required");
    return runCommand((svc) => svc.listOrderItems({ journeyOrderId: q.journeyOrderId!, status: q.status, travelerRef: q.travelerRef, segmentRef: q.segmentRef, limit: limit(request), offset: offset(request) }));
  }));

  app.setNotFoundHandler((request, reply) => kitSendError(reply, 404, "NOT_FOUND", `Route ${request.method} ${request.url} was not found`, requestContext(request)));
  app.setErrorHandler((error, request, reply) => {
    const ctx = requestContext(request);
    if (error instanceof ValidationError) kitSendError(reply, 400, "VALIDATION_FAILED", error.message, ctx);
    else if (isDomainError(error)) kitSendError(reply, error.code === "NOT_FOUND" ? 404 : error.code === "PRECONDITION_FAILED" ? 409 : 422, error.code === "NOT_FOUND" ? "NOT_FOUND" : error.code === "PRECONDITION_FAILED" ? "PRECONDITION_FAILED" : "DOMAIN_RULE_VIOLATION", error.message, ctx, { domainCode: error.code });
    else kitSendError(reply, 500, "INTERNAL_ERROR", errorMessage(error), ctx);
  });
  return app;
}

function stateChanging(app: FastifyInstance, store: IdempotencyStore, method: "POST" | "PUT" | "DELETE", path: string, operation: (request: FastifyRequest, context: RequestContext, reply: FastifyReply) => Promise<{ statusCode: number; body: unknown }>, _noop: () => void): void {
  app.route({ method, url: path, handler: async (request, reply) => {
    const ctx = requestContext(request);
    await handleIdempotency({ key: headerValue(request.headers["idempotency-key"]), store, fingerprint: requestFingerprint(request.method, request.url.split("?")[0] ?? request.url, request.body ?? null), context: ctx, reply, operation: () => operation(request, ctx, reply) });
    return reply;
  } });
}

async function handleRead(reply: FastifyReply, request: FastifyRequest, operation: () => Promise<unknown>): Promise<FastifyReply> {
  try { return reply.status(200).send(await operation()); }
  catch (error) {
    const ctx = requestContext(request);
    if (error instanceof ValidationError) kitSendError(reply, 400, "VALIDATION_FAILED", error.message, ctx);
    else if (isDomainError(error)) kitSendError(reply, 404, "NOT_FOUND", error.message, ctx);
    else throw error;
    return reply;
  }
}

function farePricingGatewayFromEnv(): AncillaryPricingGateway | undefined {
  const baseUrl = process.env.FARE_PRICING_URL?.trim();
  return baseUrl ? new HttpFarePricingGateway(baseUrl) : undefined;
}
function requestContext(request: AppRequest): RequestContext { return kitRequestContext({ headers: request.headers, id: request.id }); }
function traceContext(request: AppRequest, context: RequestContext = requestContext(request)): RequestTraceContext { return { ...context, method: request.method, url: request.url }; }
function quoteInput(request: FastifyRequest): { expectedVersion: number; validitySeconds?: number; pricing?: Parameters<AncillaryApplicationService["quoteOffer"]>[1]["pricing"]; priceQuoteIdempotencyKey?: string } { return { ...(request.body as { expectedVersion: number; validitySeconds?: number; pricing?: Parameters<AncillaryApplicationService["quoteOffer"]>[1]["pricing"] }), priceQuoteIdempotencyKey: headerValue(request.headers["idempotency-key"]) }; }
type AppRequest = FastifyRequest;
function healthBody(): HealthStatus { return { status: health(), service: serviceProfile }; }
function probeBody(probe: ProbeStatus["probe"]): ProbeStatus { return { status: health(), probe }; }

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
  if (reportUnlive(reply)) {
    return { status: "unhealthy", probe: "live" };
  }
  return probeBody("live");
}

/**
 * `/healthz` answers the service-profile body here rather than the probe body,
 * and existing clients depend on that shape, so only the status code changes
 * when liveness fails.
 */
function healthzBody(reply: FastifyReply): HealthStatus | ProbeStatus {
  return reportUnlive(reply) ? { status: "unhealthy", probe: "live" } : healthBody();
}

function reportUnlive(reply: FastifyReply): boolean {
  const probe = livenessProbe();
  if (probe.live) {
    return false;
  }
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
  return true;
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
async function readyBody(reply: FastifyReply, storage?: AppStorage): Promise<ProbeStatus> {
  const ready = storage ? await storage.ready() : true;
  if (ready) return { status: "ok", probe: "ready" };
  reply.status(503);
  const schema = storage?.schema?.();
  return schema && schema.schema !== "applied" ? { status: "not_ready", probe: "ready", schema } : { status: "not_ready", probe: "ready" };
}
function param(request: FastifyRequest, name: string): string { return (request.params as Record<string, string>)[name] ?? ""; }
function query(request: FastifyRequest): Record<string, string | undefined> { return request.query as Record<string, string | undefined>; }
function limit(request: FastifyRequest): number { return Math.min(Number(query(request).limit ?? 20), 100); }
function offset(request: FastifyRequest): number { return Number(query(request).offset ?? 0); }
class ValidationError extends Error {}
function replyNoop(): void {}
