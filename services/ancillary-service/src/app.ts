import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";

import { AncillaryApplicationService, InMemoryAncillaryRepository, isDomainError, type AncillaryRepository } from "./application.js";
import { serviceProfile } from "./profile.js";
import { HttpFarePricingGateway, type AncillaryPricingGateway } from "./pricing.js";
import { InMemoryIdempotencyStore, errorMessage, handleIdempotency, headerValue, requestContext as kitRequestContext, requestFingerprint, sendError as kitSendError, type ErrorEnvelope, type EventPublisher, type IdempotencyStore, type RequestContext } from "@trainticket/ts-kit";

export type HealthStatus = Readonly<{ status: "ok"; service: typeof serviceProfile }>;
export type ProbeStatus = Readonly<{ status: "ok" | "not_ready"; probe: "live" | "ready" }>;
export type ServiceMetadata = Readonly<{ service: typeof serviceProfile; observability: Readonly<{ tracing: "opt-in"; default: "noop" }> }>;
export type ErrorBody = ErrorEnvelope;
export type { RequestContext };
export type RequestTraceContext = RequestContext & Readonly<{ method: string; url: string }>;
export type TraceResult = RequestTraceContext & Readonly<{ statusCode: number }>;
export type TraceSpan = Readonly<{ end?: (result: TraceResult) => void | Promise<void> }>;
export type InstrumentationHooks = Readonly<{ onRequest?: (context: RequestContext) => void | Promise<void>; startSpan?: (context: RequestTraceContext) => void | TraceSpan | Promise<void | TraceSpan> }>;

type OTelSpan = Readonly<{ setAttribute?: (key: string, value: string | number) => void; setAttributes?: (attributes: Record<string, string | number>) => void; end?: () => void }>;
type OTelTracer = Readonly<{ startSpan: (name: string, options?: Record<string, unknown>) => OTelSpan }>;

type AppStorage = Readonly<{ ready: () => boolean | Promise<boolean>; runCommand?: <T>(operation: (service: AncillaryApplicationService) => Promise<T>) => Promise<T> }>;
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
  app.get("/healthz", async () => healthBody());
  app.get("/metadata", async () => metadata());
  app.get("/live", async () => probeBody("live"));
  app.get("/livez", async () => probeBody("live"));
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
async function readyBody(reply: FastifyReply, storage?: AppStorage): Promise<ProbeStatus> { const ready = storage ? await storage.ready() : true; if (!ready) reply.status(503); return { status: ready ? "ok" : "not_ready", probe: "ready" }; }
function param(request: FastifyRequest, name: string): string { return (request.params as Record<string, string>)[name] ?? ""; }
function query(request: FastifyRequest): Record<string, string | undefined> { return request.query as Record<string, string | undefined>; }
function limit(request: FastifyRequest): number { return Math.min(Number(query(request).limit ?? 20), 100); }
function offset(request: FastifyRequest): number { return Number(query(request).offset ?? 0); }
class ValidationError extends Error {}
function replyNoop(): void {}
