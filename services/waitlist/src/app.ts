import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";
import { InMemoryEventPublisher, InMemoryIdempotencyStore, errorMessage, handleIdempotency, headerValue, requestContext as kitRequestContext, livenessProbe, requestFingerprint, sendError, type EventPublisher, type IdempotencyStore, type RequestContext, type SchemaDetail } from "@trainticket/ts-kit";
import { DomainError } from "./domain.js";
import { InMemoryWaitlistRepository, WaitlistApplicationService, type JoinWaitlistRequest } from "./application.js";
import type { CapacityAvailabilityClient, FarePricingClient, JourneyOrderClient, OfferManagementClient, WaitlistRepository } from "./promotion.js";
import { serviceProfile } from "./profile.js";
import type { EventConsumptionState } from "./subscriber-retry.js";

type AppStorage = Readonly<{
  ready: () => boolean | Promise<boolean>;
  /** Optional migration retry state; reported on `/readyz` while migrations are still being applied. */
  schema?: () => SchemaDetail;
  runCommand: <T>(operation: (repository: WaitlistRepository, publisher: EventPublisher) => Promise<T>) => Promise<T>;
}>;

export type AppDependencies = Readonly<{
  repository?: WaitlistRepository;
  publisher?: EventPublisher;
  idempotencyStore?: IdempotencyStore;
  farePricing?: FarePricingClient;
  capacityAvailability?: CapacityAvailabilityClient;
  journeyOrder?: JourneyOrderClient;
  offerManagement?: OfferManagementClient;
  now?: () => Date;
  storage?: AppStorage;
  applicationService?: WaitlistApplicationService;
  /**
   * Reports whether the stream consumer is live or still retrying subscribe.
   * Surfaced on `/readyz` for observability; see `readyBody`.
   */
  eventConsumption?: () => EventConsumptionState;
}>;

const defaultRepository = new InMemoryWaitlistRepository();
const defaultPublisher = new InMemoryEventPublisher();
const defaultIdempotency = new InMemoryIdempotencyStore();

export function health(): "ok" { return "ok"; }
export function metadata() { return { service: serviceProfile, observability: { tracing: "opt-in", default: "noop" } }; }
export function resetWaitlistStore(): void { defaultRepository.clear(); defaultPublisher.reset(); defaultIdempotency.clear(); }

export function createApp(dependencies: AppDependencies = {}): FastifyInstance {
  const app = Fastify({ logger: false });
  const runCommand = dependencies.storage?.runCommand ?? inMemoryCommandRunner(dependencies);
  const sharedApplicationService = dependencies.applicationService;
  const commandService = dependencies.storage || !sharedApplicationService
    ? (repository: WaitlistRepository, publisher: EventPublisher) => new WaitlistApplicationService(
      repository,
      publisher,
      dependencies.farePricing,
      dependencies.capacityAvailability,
      dependencies.journeyOrder,
      dependencies.now,
      dependencies.offerManagement,
    )
    : () => sharedApplicationService;
  const idempotencyStore = dependencies.idempotencyStore ?? defaultIdempotency;

  app.addHook("onRequest", async (request, reply) => {
    const ctx = requestContext(request);
    reply.header("x-request-id", ctx.requestId);
    reply.header("x-correlation-id", ctx.correlationId);
  });

  app.get("/health", async () => ({ status: health(), service: serviceProfile }));
  app.get("/healthz", async (_request, reply) => healthzBody(reply));
  app.get("/live", async (_request, reply) => liveBody(reply));
  app.get("/livez", async (_request, reply) => liveBody(reply));
  app.get("/ready", async (_request, reply) => readyBody(reply, dependencies.storage, dependencies.eventConsumption));
  app.get("/readyz", async (_request, reply) => readyBody(reply, dependencies.storage, dependencies.eventConsumption));
  app.get("/metadata", async () => metadata());

  stateChanging(app, idempotencyStore, "POST", "/api/v1/waitlist-requests", async (request, ctx) => ({
    statusCode: 201,
    body: await runCommand((repository, publisher) => commandService(repository, publisher).join(createBody(request.body), ctx.correlationId)),
  }));
  stateChanging(app, idempotencyStore, "POST", "/api/v1/waitlist/entries", async (request, ctx) => ({
    statusCode: 201,
    body: await runCommand((repository, publisher) => commandService(repository, publisher).join(createBody(request.body), ctx.correlationId)),
  }));
  app.get("/api/v1/waitlist-requests", async (request, reply) => handleRead(reply, request, () => {
    const params = query(request);
    const travelerRef = requiredQueryString(params, "travelerRef");
    return runCommand((repository, publisher) => commandService(repository, publisher).listByTraveler(
      travelerRef,
      params.status as any,
      parsePageNumber(params.limit, 20, 100),
      parsePageNumber(params.offset, 0, Number.MAX_SAFE_INTEGER),
    ));
  }));
  app.get("/api/v1/waitlist-requests/:waitlistRequestId", async (request, reply) => handleRead(reply, request, () => (
    runCommand((repository, publisher) => commandService(repository, publisher).get(param(request, "waitlistRequestId")))
  )));
  app.get("/api/v1/waitlist/entries/:entryId", async (request, reply) => handleRead(reply, request, () => (
    runCommand((repository, publisher) => commandService(repository, publisher).get(param(request, "entryId")))
  )));
  stateChanging(app, idempotencyStore, "POST", "/api/v1/waitlist-requests/:waitlistRequestId/cancel", async (request, ctx) => ({
    statusCode: 200,
    body: await runCommand((repository, publisher) => commandService(repository, publisher).cancel(param(request, "waitlistRequestId"), cancelBody(request.body), ctx.correlationId)),
  }));
  stateChanging(app, idempotencyStore, "DELETE", "/api/v1/waitlist/entries/:entryId", async (request, ctx) => ({
    statusCode: 200,
    body: await runCommand((repository, publisher) => commandService(repository, publisher).cancel(param(request, "entryId"), cancelBody(request.body), ctx.correlationId)),
  }));
  stateChanging(app, idempotencyStore, "POST", "/api/v1/waitlist/entries/:entryId/accept", async (request, ctx) => ({
    statusCode: 200,
    body: await runCommand((repository, publisher) => commandService(repository, publisher).accept(param(request, "entryId"), optionalObjectBody(request.body), ctx.correlationId)),
  }));
  stateChanging(app, idempotencyStore, "POST", "/api/v1/waitlist/archive-sweep", async (_request, _ctx) => ({
    statusCode: 200,
    body: { archived: await runCommand((repository, publisher) => commandService(repository, publisher).archiveTerminalRequests()) },
  }));
  app.get("/api/v1/waitlist/segments/:segmentRef/:departureDate/queue", async (request, reply) => handleRead(reply, request, () => (
    runCommand((repository, publisher) => commandService(repository, publisher).queueInfo(param(request, "segmentRef"), param(request, "departureDate"), query(request).seatClass, query(request).entryId))
  )));

  app.setNotFoundHandler((request, reply) => sendError(reply, 404, "NOT_FOUND", `Route ${request.method} ${request.url} was not found`, requestContext(request)));
  app.setErrorHandler((error, request, reply) => {
    const ctx = requestContext(request);
    if (error instanceof DomainError) {
      sendError(reply, statusForDomainError(error), error.code, error.message, ctx, { domainCode: error.code });
      return;
    }
    if (isJsonBodyParseError(error, request)) {
      sendError(reply, 400, "VALIDATION_FAILED", errorMessage(error), ctx, { domainCode: "VALIDATION_FAILED" });
      return;
    }
    sendError(reply, 500, "INTERNAL_ERROR", errorMessage(error), ctx);
  });

  return app;
}

function inMemoryCommandRunner(dependencies: AppDependencies): AppStorage["runCommand"] {
  return (operation) => operation(dependencies.repository ?? defaultRepository, dependencies.publisher ?? defaultPublisher);
}

/**
 * Readiness.
 *
 * DECISION: while the subscriber is retrying subscribe, `/readyz` stays 200 and
 * reports `eventConsumption: "retrying"` in its body. It does NOT go 503.
 * Same conclusion as loyalty-membership, and for the same reasons, plus one
 * that is specific to waitlist:
 *
 *  - The Helm chart (`deploy/helm/train-ticket/values.yaml`) gives waitlist
 *    `replicas: 1` behind a ClusterIP Service. 503 here removes the only
 *    endpoint, so the synchronous join/cancel/accept/queue-info API -- which
 *    does not touch the consumer at all -- would start refusing connections
 *    during a Redis outage. That turns a partial outage into a total one, the
 *    very shape of the 2026-09-06 incident.
 *  - `deploy/e2e/14-waitlist.sh` calls `http://waitlist:8080/api/v1/...` by
 *    Service DNS, and `deploy/e2e/12-restart.sh` gates on `kubectl rollout
 *    status` for every deployment. A never-Ready pod never becomes Available,
 *    so failing readiness during a subscribe retry would hang the e2e rollout
 *    gate instead of reporting one degraded consumer.
 *  - Readiness has no grace period and no self-healing: it withdraws endpoints
 *    and waits. The background retry already restores consumption without a
 *    restart, so surrendering HTTP availability buys nothing.
 *  - Nothing polls this endpoint but the kubelet -- no ingress, no gateway, and
 *    no service calls waitlist over HTTP (waitlist is a caller of fare-pricing,
 *    capacity-availability, journey-order and offer-management, not a callee).
 *    A 503 would therefore signal nothing to anyone; it would only delete the
 *    endpoint.
 *
 * "Consumption is permanently dead" is liveness' question, and that path is
 * already correct: after a successful subscribe ts-kit registers
 * `redis-consumer.waitlist`, so a later wedge fails `/healthz` once the grace
 * period elapses. During the retry window the never-healthy guard keeps
 * liveness green deliberately, so the state is reported in the readiness body
 * rather than encoded as an endpoint withdrawal.
 *
 * Postgres readiness keeps its existing 503: that genuinely does break the
 * HTTP API, so withdrawing the endpoint is right there.
 *
 * DATABASE MIGRATIONS are the deliberate mirror image of the subscriber case
 * above, and the contrast is the reason both decisions are right. A retrying
 * SUBSCRIBER leaves the HTTP API fully working, so 503 would only destroy
 * working capacity. Retrying MIGRATIONS mean the tables the HTTP API reads and
 * writes do not exist yet, so every request would 500 with `relation "..." does
 * not exist`; serving reads against a missing schema is worse than serving them
 * with a dead consumer, and an honest 503 beats a 500 a caller may treat as
 * terminal. So migrations keep the 503 -- and now report `schema` in the body
 * so the state is diagnosable -- while the retry makes that 503 temporary
 * rather than the permanent wedge it used to be. See `superviseMigrations` in
 * ts-kit for the full reasoning, including why liveness stays green throughout.
 */
async function readyBody(reply: FastifyReply, storage?: AppStorage, eventConsumption?: () => EventConsumptionState): Promise<{ status: "ok" | "not_ready"; probe: "ready"; eventConsumption?: EventConsumptionState; schema?: SchemaDetail }> {
  const ready = storage ? await storage.ready() : true;
  if (!ready) reply.status(503);
  const consumption = eventConsumption?.();
  const schema = ready ? undefined : storage?.schema?.();
  const body = { status: ready ? "ok" as const : "not_ready" as const, probe: "ready" as const };
  const withConsumption = consumption ? { ...body, eventConsumption: consumption } : body;
  return schema && schema.schema !== "applied" ? { ...withConsumption, schema } : withConsumption;
}

export type LiveStatus = Readonly<{ status: "ok" | "unhealthy"; probe: "live" }>;

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
function liveBody(reply: FastifyReply): LiveStatus {
  return reportUnlive(reply) ? { status: "unhealthy", probe: "live" } : { status: health(), probe: "live" };
}

/**
 * `/healthz` answers the service-profile body here rather than the probe body,
 * and existing clients depend on that shape, so only the status code changes
 * when liveness fails.
 */
function healthzBody(reply: FastifyReply): LiveStatus | { status: "ok"; service: typeof serviceProfile } {
  return reportUnlive(reply) ? { status: "unhealthy", probe: "live" } : { status: health(), service: serviceProfile };
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

function stateChanging(app: FastifyInstance, store: IdempotencyStore, method: "POST" | "DELETE", path: string, operation: (request: FastifyRequest, context: RequestContext) => Promise<{ statusCode: number; body: unknown }>): void {
  app.route({ method, url: path, handler: async (request, reply) => {
    const ctx = requestContext(request);
    await handleIdempotency({ key: headerValue(request.headers["idempotency-key"]), store, fingerprint: requestFingerprint(request.method, request.url.split("?")[0] ?? request.url, request.body ?? null), context: ctx, reply, operation: () => operation(request, ctx) });
    return reply;
  } });
}

async function handleRead(reply: FastifyReply, request: FastifyRequest, operation: () => Promise<unknown>): Promise<FastifyReply> {
  try { return reply.status(200).send(await operation()); }
  catch (error) {
    const ctx = requestContext(request);
    if (error instanceof DomainError) sendError(reply, statusForDomainError(error), error.code, error.message, ctx, { domainCode: error.code });
    else throw error;
    return reply;
  }
}

function requestContext(request: FastifyRequest): RequestContext { return kitRequestContext({ headers: request.headers, id: request.id }); }
function param(request: FastifyRequest, name: string): string { return (request.params as Record<string, string>)[name] ?? ""; }
function query(request: FastifyRequest): Record<string, string | undefined> { return request.query as Record<string, string | undefined>; }
function statusForDomainError(error: DomainError): number {
  if (error.code === "NOT_FOUND") return 404;
  if (error.code === "CONFLICT" || error.code === "PRECONDITION_FAILED" || error.code === "INVALID_TRANSITION") return 409;
  return 400;
}
function createBody(body: unknown): JoinWaitlistRequest {
  const parsed = objectBody(body);
  for (const field of ["accountId", "travelerRef", "segmentRef", "deadline", "paymentGuaranteeRef", "itineraryRef", "intentFingerprint"]) requiredString(parsed, field);
  return parsed as unknown as JoinWaitlistRequest;
}
function cancelBody(body: unknown): Record<string, unknown> {
  const parsed = objectBody(body);
  requiredString(parsed, "reason");
  return parsed;
}
function objectBody(body: unknown): Record<string, unknown> {
  if (typeof body !== "object" || body === null || Array.isArray(body)) throw new DomainError("VALIDATION_FAILED", "Request body must be a JSON object");
  return body as Record<string, unknown>;
}
function optionalObjectBody(body: unknown): Record<string, unknown> {
  if (body === undefined) return {};
  return objectBody(body);
}
function requiredString(body: Record<string, unknown>, field: string): string {
  const value = body[field];
  if (typeof value !== "string" || value.trim().length === 0) throw new DomainError("VALIDATION_FAILED", `${field} is required`);
  return value;
}
function requiredQueryString(params: Record<string, string | undefined>, field: string): string {
  const value = params[field];
  if (typeof value !== "string" || value.trim().length === 0) throw new DomainError("VALIDATION_FAILED", `${field} is required`);
  return value;
}
function isJsonBodyParseError(error: unknown, request: FastifyRequest): boolean {
  if (request.method !== "POST" && request.method !== "DELETE") return false;
  if (typeof error !== "object" || error === null) return false;
  const candidate = error as { code?: unknown; statusCode?: unknown };
  return candidate.statusCode === 400 || candidate.code === "FST_ERR_CTP_EMPTY_JSON_BODY" || candidate.code === "FST_ERR_CTP_INVALID_JSON_BODY";
}
function parsePageNumber(value: string | undefined, fallback: number, max: number): number {
  const parsed = Number.parseInt(value ?? "", 10);
  if (!Number.isFinite(parsed) || parsed < 0) return fallback;
  return Math.min(parsed, max);
}
