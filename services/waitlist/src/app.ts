import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";
import { InMemoryEventPublisher, InMemoryIdempotencyStore, errorMessage, handleIdempotency, headerValue, requestContext as kitRequestContext, requestFingerprint, sendError, type EventPublisher, type IdempotencyStore, type RequestContext } from "@trainticket/ts-kit";
import { DomainError } from "./domain.js";
import { InMemoryWaitlistRepository, WaitlistApplicationService, toWaitlistRequestResource, type JoinWaitlistRequest, type JourneyOrderClient } from "./application.js";
import type { CapacityAvailabilityClient, FarePricingClient, OfferManagementClient, WaitlistRepository } from "./promotion.js";
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
  const commandService = (repository: WaitlistRepository, publisher: EventPublisher) => new WaitlistApplicationService(
    repository,
    publisher,
    dependencies.farePricing,
    dependencies.capacityAvailability,
    dependencies.journeyOrder,
    dependencies.now,
    dependencies.offerManagement,
  );
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
    body: toWaitlistRequestResource(await runCommand((repository, publisher) => commandService(repository, publisher).join(toJoinWaitlistRequest(request.body as Record<string, unknown>), ctx.correlationId))),
  }));
  app.get("/api/v1/waitlist-requests/:waitlistRequestId", async (request, reply) => handleRead(reply, request, () => (
    runCommand((repository, publisher) => commandService(repository, publisher).getResource(param(request, "waitlistRequestId")))
  )));
  stateChanging(app, idempotencyStore, "POST", "/api/v1/waitlist-requests/:waitlistRequestId/cancel", async (request, ctx) => ({
    statusCode: 200,
    body: await runCommand((repository, publisher) => commandService(repository, publisher).cancel(param(request, "waitlistRequestId"), cancelReason(request.body), ctx.correlationId)),
  }));
  app.get("/api/v1/waitlist/segments/:segmentRef/:departureDate/queue", async (request, reply) => handleRead(reply, request, () => (
    runCommand((repository, publisher) => commandService(repository, publisher).queueInfo(param(request, "segmentRef"), param(request, "departureDate"), query(request).seatClass, query(request).entryId))
  )));

  app.setNotFoundHandler((request, reply) => sendError(reply, 404, "NOT_FOUND", `Route ${request.method} ${request.url} was not found`, requestContext(request)));
  app.setErrorHandler((error, request, reply) => {
    const ctx = requestContext(request);
    if (error instanceof DomainError) {
      const status = error.code === "NOT_FOUND" ? 404 : error.code === "PRECONDITION_FAILED" || error.code === "INVALID_TRANSITION" ? 409 : 400;
      sendError(reply, status, error.code, error.message, ctx, { domainCode: error.code });
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
    if (error instanceof DomainError) sendError(reply, error.code === "NOT_FOUND" ? 404 : 409, error.code, error.message, ctx, { domainCode: error.code });
    else throw error;
    return reply;
  }
}

function toJoinWaitlistRequest(body: Record<string, unknown>): JoinWaitlistRequest {
  const travelerRef = stringBody(body, "travelerRef");
  const travelClass = stringBody(body, "travelClass") ?? stringBody(body, "seatClass") ?? "SECOND";
  return {
    accountId: stringBody(body, "accountId") ?? "",
    travelerRefs: travelerRef ? [travelerRef] : arrayBody(body, "travelerRefs"),
    segmentRef: stringBody(body, "segmentRef") ?? "",
    departureDate: (stringBody(body, "deadline") ?? stringBody(body, "departureDate") ?? "").slice(0, 10),
    seatClass: travelClass as JoinWaitlistRequest["seatClass"],
    itineraryRef: stringBody(body, "itineraryRef"),
    deadline: stringBody(body, "deadline"),
    paymentGuaranteeRef: stringBody(body, "paymentGuaranteeRef"),
    intentFingerprint: stringBody(body, "intentFingerprint"),
  };
}

function stringBody(body: Record<string, unknown>, field: string): string | undefined {
  return typeof body[field] === "string" ? body[field] as string : undefined;
}

function arrayBody(body: Record<string, unknown>, field: string): readonly string[] {
  const value = body[field];
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

function cancelReason(body: unknown): string {
  return isRecord(body) && typeof body.reason === "string" && body.reason.trim().length > 0 ? body.reason : "user-requested";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function requestContext(request: FastifyRequest): RequestContext { return kitRequestContext({ headers: request.headers, id: request.id }); }
function param(request: FastifyRequest, name: string): string { return (request.params as Record<string, string>)[name] ?? ""; }
function query(request: FastifyRequest): Record<string, string | undefined> { return request.query as Record<string, string | undefined>; }
