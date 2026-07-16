import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";
import { InMemoryEventPublisher, InMemoryIdempotencyStore, errorMessage, handleIdempotency, headerValue, requestContext as kitRequestContext, requestFingerprint, sendError, type EventPublisher, type IdempotencyStore, type RequestContext } from "@trainticket/ts-kit";
import { DomainError } from "./domain.js";
import { InMemoryWaitlistRepository, WaitlistApplicationService, type JoinWaitlistRequest } from "./application.js";
import type { CapacityAvailabilityClient, FarePricingClient, JourneyOrderClient, OfferManagementClient, WaitlistRepository } from "./promotion.js";
import { serviceProfile } from "./profile.js";

type AppStorage = Readonly<{
  ready: () => boolean | Promise<boolean>;
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
  app.get("/healthz", async () => ({ status: health(), service: serviceProfile }));
  app.get("/live", async () => ({ status: health(), probe: "live" }));
  app.get("/livez", async () => ({ status: health(), probe: "live" }));
  app.get("/ready", async (_request, reply) => readyBody(reply, dependencies.storage));
  app.get("/readyz", async (_request, reply) => readyBody(reply, dependencies.storage));
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

async function readyBody(reply: FastifyReply, storage?: AppStorage): Promise<{ status: "ok" | "not_ready"; probe: "ready" }> {
  const ready = storage ? await storage.ready() : true;
  if (!ready) reply.status(503);
  return { status: ready ? "ok" : "not_ready", probe: "ready" };
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
