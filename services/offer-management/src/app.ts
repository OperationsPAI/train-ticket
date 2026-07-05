import Fastify, { type FastifyInstance, type FastifyRequest } from "fastify";

import {
  InMemoryOfferRepository,
  OfferApplicationService,
  isDomainError,
  type OfferRepository,
} from "./application/offers.js";
import { PublishFailed } from "./ports/messaging.js";
import {
  InMemoryUpstreamStateRepository,
  buildQuoteOfferCommand,
  type QuoteOfferRequest,
  type UpstreamStateRepository,
} from "./application/upstream-state.js";
import { type QuoteOfferCommand } from "./domain.js";
import { type EventPublisher } from "./ports/messaging.js";
import {
  InMemoryIdempotencyStore,
  errorMessage,
  handleIdempotency,
  headerValue,
  requestFingerprint,
  sendError,
  type ErrorEnvelope,
} from "@trainticket/ts-kit";
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

export type ErrorBody = ErrorEnvelope;

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
const defaultIdempotencyStore = new InMemoryIdempotencyStore();

export function resetIdempotencyStore(): void {
  defaultIdempotencyStore.clear();
}

// ---------------------------------------------------------------------------
// Offer application service dependencies
// ---------------------------------------------------------------------------
type AppDependencies = Readonly<{
  repository?: OfferRepository;
  publisher?: EventPublisher;
  quoteCommandFactory?: (request: QuoteOfferRequest) => QuoteOfferCommand | Promise<QuoteOfferCommand>;
  upstreamRepository?: UpstreamStateRepository;
  idempotencyStore?: InMemoryIdempotencyStore;
}>;

const defaultOfferRepository = new InMemoryOfferRepository();
const defaultUpstreamRepository = new InMemoryUpstreamStateRepository();

export function resetOfferStore(): void {
  defaultOfferRepository.clear();
  defaultUpstreamRepository.clear();
}

// ---------------------------------------------------------------------------
// App factory
// ---------------------------------------------------------------------------
export function createApp(instrumentation: InstrumentationHooks = {}, dependencies: AppDependencies = {}): FastifyInstance {
  const app: FastifyInstance = Fastify({ logger: false });
  const upstreamRepository = dependencies.upstreamRepository ?? defaultUpstreamRepository;
  const offerService = new OfferApplicationService(
    dependencies.repository ?? defaultOfferRepository,
    dependencies.publisher,
    dependencies.quoteCommandFactory ?? ((request) => buildQuoteOfferCommand(upstreamRepository, request)),
  );
  const idempotencyStore = dependencies.idempotencyStore ?? defaultIdempotencyStore;
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
  app.get("/healthz", async () => healthBody());
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

    try {
      await handleIdempotency({
        key: headerValue(request.headers["idempotency-key"]),
        store: idempotencyStore,
        fingerprint: requestFingerprint(request.method, request.url.split("?")[0] ?? request.url, request.body ?? null),
        context: ctx,
        reply,
        operation: async () => {
          const validationError = validateQuoteOfferRequest(request.body);
          if (validationError) {
            throw new ValidationError(validationError);
          }

          const { accountId, channelId, itineraryRef, travelerRefs, quoteRequestId } = request.body;
          const { response: responseBody } = await offerService.quoteOffer({
            accountId: accountId!,
            channelId: channelId!,
            itineraryRef: itineraryRef!,
            travelerRefs: travelerRefs!,
            quoteRequestId,
          }, ctx.correlationId);

          return { statusCode: 201, body: responseBody };
        },
      });
      return reply;
    } catch (error) {
      if (error instanceof ValidationError) {
        sendError(reply, 400, "VALIDATION_FAILED", error.message, ctx);
        return reply;
      }
      if (isDomainError(error)) {
        sendError(reply, 422, "DOMAIN_RULE_VIOLATION", error.message, ctx, { domainCode: error.code });
        return reply;
      }
      if (error instanceof PublishFailed) {
        sendError(reply, 503, "UNAVAILABLE", "Offer event could not be published", ctx);
        return reply;
      }
      throw error;
    }
  });

  // -----------------------------------------------------------------------
  // Offer Management API — GET /api/v1/offers/:offerId
  // -----------------------------------------------------------------------
  app.get<{
    Params: { offerId: string };
  }>("/api/v1/offers/:offerId", async (request, reply) => {
    const ctx = requestContext(request);
    const { offerId } = request.params;

    const offer = await offerService.getOffer(offerId);
    if (!offer) {
      sendError(reply, 404, "NOT_FOUND", `Offer ${offerId} not found`, ctx);
      return reply;
    }

    return reply.status(200).send(offer);
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

class ValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ValidationError";
  }
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
  const requestId = headerValue(request.headers["x-request-id"]) ?? request.id;
  const correlationId = headerValue(request.headers["x-correlation-id"]) ?? requestId;
  return { requestId, correlationId };
}

type AppRequest = FastifyRequest;