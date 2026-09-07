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
  livenessProbe,
  requestFingerprint,
  requestContext as kitRequestContext,
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

export type ErrorBody = ErrorEnvelope;

export type { RequestContext };

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
  idempotencyStore?: IdempotencyStore;
  storage?: AppStorage;
}>;

export type AppStorage = Readonly<{
  ready: () => boolean | Promise<boolean>;
  /** Optional migration retry state; reported on `/readyz` while migrations are still being applied. */
  schema?: () => SchemaDetail;
  runCommand?: <T>(upstreamRepository: UpstreamStateRepository, operation: (application: OfferApplicationService) => Promise<T>) => Promise<T>;
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
  const runWithOfferService = dependencies.storage?.runCommand ?? (<T>(_upstream: UpstreamStateRepository, operation: (service: OfferApplicationService) => Promise<T>) => operation(offerService));
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
  app.get("/healthz", async (_request, reply) => healthzBody(reply));
  app.get("/metadata", async () => metadata());
  app.get("/live", async (_request, reply) => liveBody(reply));
  app.get("/livez", async (_request, reply) => liveBody(reply));
  app.get("/ready", async (_request, reply) => readyBody(reply, dependencies.storage));
  app.get("/readyz", async (_request, reply) => readyBody(reply, dependencies.storage));

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
          const { response: responseBody } = await runWithOfferService(upstreamRepository, (service) => service.quoteOffer({
            accountId: accountId!,
            channelId: channelId!,
            itineraryRef: itineraryRef!,
            travelerRefs: travelerRefs!,
            quoteRequestId,
          }, ctx.correlationId));

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

    const offer = await runWithOfferService(upstreamRepository, (service) => service.getOffer(offerId));
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
function healthzBody(reply: { status: (statusCode: number) => unknown }): HealthStatus | ProbeStatus {
  return reportUnlive(reply) ? { status: "unhealthy", probe: "live" } : healthBody();
}

function reportUnlive(reply: { status: (statusCode: number) => unknown }): boolean {
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
  return kitRequestContext(request);
}

type AppRequest = FastifyRequest;