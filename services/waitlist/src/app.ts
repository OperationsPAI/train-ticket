import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";
import { InMemoryEventPublisher, InMemoryIdempotencyStore, errorMessage, handleIdempotency, headerValue, requestContext as kitRequestContext, requestFingerprint, sendError, type EventPublisher, type IdempotencyStore, type RequestContext } from "@trainticket/ts-kit";
import { DomainError, type WaitlistStatus } from "./domain.js";
import { InMemoryWaitlistRepository, WaitlistApplicationService, type JourneyOrderClient } from "./application.js";
import type { CapacityAvailabilityClient, FarePricingClient, OfferManagementClient, WaitlistRepository } from "./promotion.js";
import { serviceProfile } from "./profile.js";

type AppStorage = Readonly<{ ready: () => boolean | Promise<boolean>; runCommand: <T>(operation: (repository: WaitlistRepository, publisher: EventPublisher) => Promise<T>) => Promise<T> }>;
export type AppDependencies = Readonly<{ repository?: WaitlistRepository; publisher?: EventPublisher; idempotencyStore?: IdempotencyStore; farePricing?: FarePricingClient; capacityAvailability?: CapacityAvailabilityClient; journeyOrder?: JourneyOrderClient; offerManagement?: OfferManagementClient; now?: () => Date; storage?: AppStorage }>;

const defaultRepository = new InMemoryWaitlistRepository();
const defaultPublisher = new InMemoryEventPublisher();
const defaultIdempotency = new InMemoryIdempotencyStore();

export function health(): "ok" { return "ok"; }
export function metadata() { return { service: serviceProfile, observability: { tracing: "opt-in", default: "noop" } }; }
export function resetWaitlistStore(): void { defaultRepository.clear(); defaultPublisher.reset(); defaultIdempotency.clear(); }

export function createApp(dependencies: AppDependencies = {}): FastifyInstance {
  const app = Fastify({ logger: false });
  const runCommand = dependencies.storage?.runCommand ?? inMemoryCommandRunner(dependencies);
  const commandService = (repository: WaitlistRepository, publisher: EventPublisher) => new WaitlistApplicationService(repository, publisher, dependencies.farePricing, dependencies.capacityAvailability, dependencies.journeyOrder, dependencies.now, dependencies.offerManagement);
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

  stateChanging(app, idempotencyStore, "POST", "/api/v1/waitlist-requests", async (request, ctx) => ({ statusCode: 201, body: await runCommand((repository, publisher) => commandService(repository, publisher).join(request.body as any, ctx.correlationId)) }));
  stateChanging(app, idempotencyStore, "POST", "/api/v1/waitlist/entries", async (request, ctx) => ({ statusCode: 201, body: await runCommand((repository, publisher) => commandService(repository, publisher).join(request.body as any, ctx.correlationId)) }));

  app.get("/api/v1/waitlist-requests/:waitlistRequestId", async (request, reply) => handleRead(reply, request, () => runCommand((repository, publisher) => commandService(repository, publisher).get(param(request, "waitlistRequestId")))));
  app.get("/api/v1/waitlist/entries/:entryId", async (request, reply) => handleRead(reply, request, () => runCommand((repository, publisher) => commandService(repository, publisher).get(param(request, "entryId")))));

  app.get("/api/v1/waitlist-requests", async (request, reply) => handleRead(reply, request, () => {
    const limit = boundedInt(query(request).limit, 20, 1, 100);
    const offset = boundedInt(query(request).offset, 0, 0, 1000000);
    return runCommand((repository, publisher) => commandService(repository, publisher).listByTraveler(query(request).travelerRef ?? "", query(request).status as WaitlistStatus | undefined, limit, offset));
  }));

  stateChanging(app, idempotencyStore, "POST", "/api/v1/waitlist-requests/:waitlistRequestId/cancel", async (request, ctx) => ({ statusCode: 200, body: await runCommand((repository, publisher) => commandService(repository, publisher).cancel(param(request, "waitlistRequestId"), request.body as any, ctx.correlationId)) }));
  stateChanging(app, idempotencyStore, "DELETE", "/api/v1/waitlist/entries/:entryId", async (request, ctx) => ({ statusCode: 200, body: await runCommand((repository, publisher) => commandService(repository, publisher).cancel(param(request, "entryId"), { reason: "USER_REQUESTED" }, ctx.correlationId)) }));

  stateChanging(app, idempotencyStore, "POST", "/api/v1/waitlist/entries/:entryId/accept", async (request, ctx) => ({ statusCode: 200, body: await runCommand((repository, publisher) => commandService(repository, publisher).accept(param(request, "entryId"), request.body as any, ctx.correlationId)) }));
  stateChanging(app, idempotencyStore, "POST", "/api/v1/waitlist-requests:archive-sweep", async () => ({ statusCode: 200, body: { items: await runCommand((repository, publisher) => commandService(repository, publisher).sweepArchived()) } }));

  app.get("/api/v1/waitlist/segments/:segmentRef/:departureDate/queue", async (request, reply) => handleRead(reply, request, () => runCommand((repository, publisher) => commandService(repository, publisher).queueInfo(param(request, "segmentRef"), param(request, "departureDate"), query(request).seatClass, query(request).entryId))));

  app.setNotFoundHandler((request, reply) => sendError(reply, 404, "NOT_FOUND", `Route ${request.method} ${request.url} was not found`, requestContext(request)));
  app.setErrorHandler((error, request, reply) => {
    const ctx = requestContext(request);
    if (error instanceof DomainError) {
      const status = error.code === "NOT_FOUND" ? 404 : error.code === "VALIDATION_FAILED" || error.code === "DOMAIN_RULE_VIOLATION" ? 400 : 409;
      sendError(reply, status, error.code, error.message, ctx, { domainCode: error.code });
      return;
    }
    sendError(reply, 500, "INTERNAL_ERROR", errorMessage(error), ctx);
  });
  return app;
}

function inMemoryCommandRunner(dependencies: AppDependencies): AppStorage["runCommand"] { return (operation) => operation(dependencies.repository ?? defaultRepository, dependencies.publisher ?? defaultPublisher); }
async function readyBody(reply: FastifyReply, storage?: AppStorage): Promise<{ status: "ok" | "not_ready"; probe: "ready" }> { const ready = storage ? await storage.ready() : true; if (!ready) reply.status(503); return { status: ready ? "ok" : "not_ready", probe: "ready" }; }
function stateChanging(app: FastifyInstance, store: IdempotencyStore, method: "POST" | "DELETE", path: string, operation: (request: FastifyRequest, context: RequestContext) => Promise<{ statusCode: number; body: unknown }>): void { app.route({ method, url: path, handler: async (request, reply) => { const ctx = requestContext(request); await handleIdempotency({ key: headerValue(request.headers["idempotency-key"]), store, fingerprint: requestFingerprint(request.method, request.url.split("?")[0] ?? request.url, request.body ?? null), context: ctx, reply, operation: () => operation(request, ctx) }); return reply; } }); }
async function handleRead(reply: FastifyReply, request: FastifyRequest, operation: () => Promise<unknown>): Promise<FastifyReply> { try { return reply.status(200).send(await operation()); } catch (error) { const ctx = requestContext(request); if (error instanceof DomainError) sendError(reply, error.code === "NOT_FOUND" ? 404 : error.code === "VALIDATION_FAILED" ? 400 : 409, error.code, error.message, ctx, { domainCode: error.code }); else throw error; return reply; } }
function boundedInt(value: string | undefined, fallback: number, min: number, max: number): number { const parsed = value ? Number.parseInt(value, 10) : fallback; if (!Number.isFinite(parsed)) return fallback; return Math.max(min, Math.min(max, parsed)); }
function requestContext(request: FastifyRequest): RequestContext { return kitRequestContext({ headers: request.headers, id: request.id }); }
function param(request: FastifyRequest, name: string): string { return (request.params as Record<string, string>)[name] ?? ""; }
function query(request: FastifyRequest): Record<string, string | undefined> { return request.query as Record<string, string | undefined>; }
