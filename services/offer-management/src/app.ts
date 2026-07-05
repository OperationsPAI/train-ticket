import { randomUUID } from "node:crypto";

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

export type ErrorBody = Readonly<{
  code: string;
  message: string;
  correlationId: string;
  details: Readonly<Record<string, unknown>>;
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

// ---------------------------------------------------------------------------
// In-memory idempotency store (single-instance only)
// ---------------------------------------------------------------------------
type IdempotencyRecord = Readonly<{
  requestBody: unknown;
  responseBody: unknown;
  statusCode: number;
}>;

const idempotencyStore = new Map<string, IdempotencyRecord>();

export function resetIdempotencyStore(): void {
  idempotencyStore.clear();
}

// ---------------------------------------------------------------------------
// Offer store (in-memory for now, replace with real repository later)
// ---------------------------------------------------------------------------
interface OfferRecord {
  offerId: string;
  offerVersion: number;
  total: { currency: string; minorUnits: number };
  expiresAt: string;
  priceGuaranteeLevel: string;
  downstreamReference: { offerId: string; offerVersion: number; priceSnapshotRef: string; ruleSnapshotRef: string };
  itineraryRef: string;
  travelerSetHash: string;
  status: string;
  accountId: string;
  channelId: string;
  quoteRequestId: string;
  createdAt: string;
  items: ReadonlyArray<Record<string, unknown>>;
  priceSnapshot: Record<string, unknown>;
  passengerMix: Record<string, unknown>;
  validityWindow: Record<string, unknown>;
  riskDisclosures: ReadonlyArray<Record<string, unknown>>;
}

const offerStore = new Map<string, OfferRecord>();

export function resetOfferStore(): void {
  offerStore.clear();
}

// ---------------------------------------------------------------------------
// Money conversion helpers
// ---------------------------------------------------------------------------
function moneyToApi(money: { amountMinor: number; currency: string }): { currency: string; minorUnits: number } {
  return { currency: money.currency, minorUnits: money.amountMinor };
}

function moneyFromApi(money: { currency: string; minorUnits: number }): { amountMinor: number; currency: string } {
  return { amountMinor: money.minorUnits, currency: money.currency };
}

// ---------------------------------------------------------------------------
// App factory
// ---------------------------------------------------------------------------
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

  // Health / metadata
  app.get("/health", async () => healthBody());
  app.get("/metadata", async () => metadata());
  app.get("/live", async () => probeBody("live"));
  app.get("/livez", async () => probeBody("live"));
  app.get("/ready", async () => probeBody("ready"));
  app.get("/readyz", async () => probeBody("ready"));

  // -----------------------------------------------------------------------
  // Offer Management API — POST /api/v1/offers
  // -----------------------------------------------------------------------
  app.post<{
    Body: {
      accountId?: string;
      channelId?: string;
      itineraryRef?: string;
      travelerRefs?: string[];
      quoteRequestId?: string;
    };
  }>("/api/v1/offers", async (request, reply) => {
    const ctx = requestContext(request);
    const idempotencyKey = request.headers["idempotency-key"] as string | undefined;

    if (!idempotencyKey) {
      return sendError(reply, 400, "VALIDATION_FAILED", "Idempotency-Key header is required on state-changing POST", ctx);
    }

    // Idempotency replay check
    const existing = idempotencyStore.get(idempotencyKey);
    if (existing) {
      // Validate request body matches (IDEMPOTENCY_KEY_REUSED if different)
      if (JSON.stringify(existing.requestBody) !== JSON.stringify(request.body)) {
        return sendError(reply, 422, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was reused with a different request body", ctx);
      }
      return reply.status(existing.statusCode).send(existing.responseBody);
    }

    // Validate request body
    const validationError = validateQuoteOfferRequest(request.body);
    if (validationError) {
      return sendError(reply, 400, "VALIDATION_FAILED", validationError, ctx);
    }

    const { accountId, channelId, itineraryRef, travelerRefs, quoteRequestId } = request.body;

    // Create the offer (domain logic)
    const offerId = `off-${randomUUID()}`;
    const offerVersion = 1;
    const now = new Date();
    const expiresAt = new Date(now.getTime() + 10 * 60 * 1000); // 10 min validity

    const offerRecord: OfferRecord = {
      offerId,
      offerVersion,
      total: { currency: "CNY", minorUnits: 0 },
      expiresAt: expiresAt.toISOString(),
      priceGuaranteeLevel: "FIXED_UNTIL_EXPIRY",
      downstreamReference: {
        offerId,
        offerVersion,
        priceSnapshotRef: "",
        ruleSnapshotRef: "",
      },
      itineraryRef: itineraryRef ?? "",
      travelerSetHash: travelerRefs ? travelerRefs.sort().join(",") : "",
      status: "Quoted",
      accountId: accountId ?? "",
      channelId: channelId ?? "",
      quoteRequestId: quoteRequestId ?? "",
      createdAt: now.toISOString(),
      items: [],
      priceSnapshot: {},
      passengerMix: {},
      validityWindow: {},
      riskDisclosures: [],
    };
    offerStore.set(offerId, offerRecord);

    const responseBody = {
      offerId: offerRecord.offerId,
      offerVersion: offerRecord.offerVersion,
      total: offerRecord.total,
      expiresAt: offerRecord.expiresAt,
      priceGuaranteeLevel: offerRecord.priceGuaranteeLevel,
      downstreamReference: offerRecord.downstreamReference,
      itineraryRef: offerRecord.itineraryRef,
      travelerSetHash: offerRecord.travelerSetHash,
    };

    // Store idempotency record
    idempotencyStore.set(idempotencyKey, {
      requestBody: request.body,
      responseBody,
      statusCode: 201,
    });

    return reply.status(201).send(responseBody);
  });

  // -----------------------------------------------------------------------
  // Offer Management API — GET /api/v1/offers/:offerId
  // -----------------------------------------------------------------------
  app.get<{
    Params: { offerId: string };
  }>("/api/v1/offers/:offerId", async (request, reply) => {
    const ctx = requestContext(request);
    const { offerId } = request.params;

    const offer = offerStore.get(offerId);
    if (!offer) {
      return sendError(reply, 404, "NOT_FOUND", `Offer ${offerId} not found`, ctx);
    }

    return reply.status(200).send({
      offerId: offer.offerId,
      offerVersion: offer.offerVersion,
      status: offer.status,
      accountId: offer.accountId,
      channelId: offer.channelId,
      quoteRequestId: offer.quoteRequestId,
      itineraryRef: offer.itineraryRef,
      travelerSetHash: offer.travelerSetHash,
      total: offer.total,
      expiresAt: offer.expiresAt,
      priceGuaranteeLevel: offer.priceGuaranteeLevel,
      downstreamReference: offer.downstreamReference,
      items: offer.items,
      priceSnapshot: offer.priceSnapshot,
      passengerMix: offer.passengerMix,
      validityWindow: offer.validityWindow,
      riskDisclosures: offer.riskDisclosures,
      createdAt: offer.createdAt,
    });
  });

  // 404 handler
  app.setNotFoundHandler((request, reply) => {
    sendError(reply, 404, "NOT_FOUND", `Route ${request.method} ${request.url} was not found`, requestContext(request));
  });

  // Error handler
  app.setErrorHandler((error, request, reply) => {
    sendError(reply, 500, "INTERNAL_ERROR", errorMessage(error), requestContext(request));
  });

  return app;
}

function validateQuoteOfferRequest(body: Record<string, unknown>): string | null {
  if (!body || typeof body !== "object") {
    return "Request body must be a JSON object";
  }
  if (typeof body.accountId !== "string" || (body.accountId as string).trim().length === 0) {
    return "accountId is required and must be a non-empty string";
  }
  if (typeof body.channelId !== "string" || (body.channelId as string).trim().length === 0) {
    return "channelId is required and must be a non-empty string";
  }
  if (typeof body.itineraryRef !== "string" || (body.itineraryRef as string).trim().length === 0) {
    return "itineraryRef is required and must be a non-empty string";
  }
  if (!Array.isArray(body.travelerRefs) || body.travelerRefs.length === 0) {
    return "travelerRefs is required and must be a non-empty array of strings";
  }
  for (const ref of body.travelerRefs) {
    if (typeof ref !== "string" || ref.trim().length === 0) {
      return "Each travelerRef must be a non-empty string";
    }
  }
  return null;
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
  const body: ErrorBody = {
    code,
    message,
    correlationId: context.correlationId,
    details: {},
  };
  reply.status(statusCode).send(body);
}
