import { randomUUID } from "node:crypto";

import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";

import { serviceProfile } from "./profile.js";

export type HealthStatus = Readonly<{
  status: "ok";
  service: typeof serviceProfile;
}>;

export type ErrorEnvelope = Readonly<{
  error: Readonly<{
    code: string;
    message: string;
    requestId: string;
    correlationId: string;
  }>;
}>;

export type InstrumentationHooks = Readonly<{
  onRequest?: (context: RequestContext) => void | Promise<void>;
}>;

export type RequestContext = Readonly<{
  requestId: string;
  correlationId: string;
}>;

export function health(): "ok" {
  return "ok";
}

export function createApp(instrumentation: InstrumentationHooks = {}): FastifyInstance {
  const app: FastifyInstance = Fastify({ logger: false });

  app.addHook("onRequest", async (request, reply) => {
    const context = requestContext(request);
    reply.header("x-request-id", context.requestId);
    reply.header("x-correlation-id", context.correlationId);
    await instrumentation.onRequest?.(context);
  });

  app.get("/health", async () => healthBody());
  app.get("/livez", async () => healthBody());
  app.get("/readyz", async () => healthBody());

  app.setNotFoundHandler((request, reply) => {
    sendError(reply, 404, "NOT_FOUND", `Route ${request.method} ${request.url} was not found`, requestContext(request));
  });

  app.setErrorHandler((error, request, reply) => {
    sendError(reply, 500, "INTERNAL_ERROR", errorMessage(error), requestContext(request));
  });

  return app;
}

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message.length > 0 ? error.message : "Internal server error";
}

function healthBody(): HealthStatus {
  return { status: health(), service: serviceProfile };
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
  const envelope: ErrorEnvelope = {
    error: {
      code,
      message,
      requestId: context.requestId,
      correlationId: context.correlationId,
    },
  };
  reply.status(statusCode).send(envelope);
}
