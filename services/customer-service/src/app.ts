import { randomUUID } from "node:crypto";

import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";

import {
  CustomerServiceApplication,
  IdempotencyKeyReusedError,
  IdempotencyStore,
  NotFoundError,
  isDomainError,
  type AssignSupportCaseRequest,
  type AttachEvidenceRequest,
  type ClassifySupportCaseRequest,
  type CloseCaseRequest,
  type EscalateCaseRequest,
  type OpenSupportCaseRequest,
  type ReopenCaseRequest,
  type ResolveCaseRequest,
} from "./application/customer-service.js";
import { InMemoryEventPublisher, newCommandId, newCorrelationId, type EventPublisher } from "./application/messaging.js";
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

export type ErrorEnvelope = Readonly<{
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

export type AppOptions = Readonly<{
  instrumentation?: InstrumentationHooks;
  publisher?: EventPublisher;
  application?: CustomerServiceApplication;
  idempotencyStore?: IdempotencyStore;
}>;

type OTelSpan = Readonly<{
  setAttribute?: (key: string, value: string | number) => void;
  setAttributes?: (attributes: Record<string, string | number>) => void;
  end?: () => void;
}>;

type OTelTracer = Readonly<{
  startSpan: (name: string, options?: Record<string, unknown>) => OTelSpan;
}>;

const channels = ["APP", "WEB", "PHONE", "IM", "EMAIL", "IN_APP_MESSAGE", "BOT", "OPERATOR_CONSOLE"] as const;
const priorities = ["LOW", "NORMAL", "HIGH", "URGENT"] as const;
const evidenceTypes = ["SCREENSHOT", "CALL_RECORDING", "CHAT_TRANSCRIPT", "EMAIL", "CHANNEL_RECEIPT", "PROVIDER_SUMMARY", "DOCUMENT", "OTHER"] as const;
const accessLevels = ["PUBLIC", "INTERNAL", "SENSITIVE", "RESTRICTED"] as const;
const closeReasons = ["RESOLVED", "ESCALATED", "DUPLICATE", "NO_FURTHER_ACTION", "CUSTOMER_CLOSED"] as const;

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

export function createApp(options: InstrumentationHooks | AppOptions = {}): FastifyInstance {
  const app: FastifyInstance = Fastify({ logger: false });
  const appOptions = normalizeOptions(options);
  const publisher = appOptions.publisher ?? new InMemoryEventPublisher();
  const application = appOptions.application ?? new CustomerServiceApplication(publisher);
  const idempotencyStore = appOptions.idempotencyStore ?? new IdempotencyStore();
  const instrumentation = appOptions.instrumentation ?? {};
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

  app.get("/health", async () => healthBody());
  app.get("/healthz", async () => probeBody("live"));
  app.get("/metadata", async () => metadata());
  app.get("/live", async () => probeBody("live"));
  app.get("/livez", async () => probeBody("live"));
  app.get("/ready", async () => probeBody("ready"));
  app.get("/readyz", async () => probeBody("ready"));

  app.post("/api/v1/support-cases", async (request, reply) => {
    const body = validateOpenSupportCase(request.body);
    const response = await withIdempotency(request, idempotencyStore, body, async () => {
      const created = await application.openSupportCase(body, requestContext(request).correlationId, newCommandId());
      return { statusCode: 201, body: openSupportCaseResponse(created) };
    });
    return reply.status(response.statusCode).send(response.body);
  });

  app.get("/api/v1/support-cases/:caseId", async (request) => {
    const { caseId } = request.params as { caseId: string };
    return supportCaseResponse(application.getSupportCase(caseId));
  });

  app.post("/api/v1/support-cases/:caseId/evidence", async (request, reply) => {
    const { caseId } = request.params as { caseId: string };
    const body = validateAttachEvidence(request.body);
    const response = await withIdempotency(request, idempotencyStore, { caseId, body }, async () => {
      const evidence = await application.attachEvidence(caseId, body, requestContext(request).correlationId, newCommandId());
      return { statusCode: 201, body: { evidenceId: evidence.evidenceId, caseId: evidence.caseId, evidenceType: evidence.evidenceType } };
    });
    return reply.status(response.statusCode).send(response.body);
  });

  app.post("/api/v1/support-cases/:caseId/classify", async (request, reply) => {
    const { caseId } = request.params as { caseId: string };
    const body = validateClassify(request.body);
    return sendIdempotentUpdate(request, reply, idempotencyStore, { caseId, body }, () =>
      application.classifySupportCase(caseId, body, operatorRef(request), requestContext(request).correlationId, newCommandId()),
    );
  });

  app.post("/api/v1/support-cases/:caseId/assign", async (request, reply) => {
    const { caseId } = request.params as { caseId: string };
    const body = validateAssign(request.body);
    return sendIdempotentUpdate(request, reply, idempotencyStore, { caseId, body }, () =>
      application.assignSupportCase(caseId, body, operatorRef(request), requestContext(request).correlationId, newCommandId()),
    );
  });

  app.post("/api/v1/support-cases/:caseId/escalate", async (request, reply) => {
    const { caseId } = request.params as { caseId: string };
    const body = validateEscalate(request.body);
    return sendIdempotentUpdate(request, reply, idempotencyStore, { caseId, body }, () =>
      application.escalateCase(caseId, body, operatorRef(request), requestContext(request).correlationId, newCommandId()),
    );
  });

  app.post("/api/v1/support-cases/:caseId/resolve", async (request, reply) => {
    const { caseId } = request.params as { caseId: string };
    const body = validateResolve(request.body);
    return sendIdempotentUpdate(request, reply, idempotencyStore, { caseId, body }, () =>
      application.resolveCase(caseId, body, operatorRef(request), requestContext(request).correlationId, newCommandId()),
    );
  });

  app.post("/api/v1/support-cases/:caseId/close", async (request, reply) => {
    const { caseId } = request.params as { caseId: string };
    const body = validateClose(request.body);
    return sendIdempotentUpdate(request, reply, idempotencyStore, { caseId, body }, () =>
      application.closeCase(caseId, body, operatorRef(request), requestContext(request).correlationId, newCommandId()),
    );
  });

  app.post("/api/v1/support-cases/:caseId/reopen", async (request, reply) => {
    const { caseId } = request.params as { caseId: string };
    const body = validateReopen(request.body);
    return sendIdempotentUpdate(request, reply, idempotencyStore, { caseId, body }, () =>
      application.reopenCase(caseId, body, requestContext(request).correlationId, newCommandId()),
    );
  });

  app.setNotFoundHandler((request, reply) => {
    sendError(reply, 404, "NOT_FOUND", `Route ${request.method} ${request.url} was not found`, requestContext(request));
  });

  app.setErrorHandler((error, request, reply) => {
    const context = requestContext(request);
    if (error instanceof ValidationError) {
      sendError(reply, 400, "VALIDATION_FAILED", error.message, context, error.details);
      return;
    }
    if (error instanceof MissingIdempotencyKeyError) {
      sendError(reply, 400, "VALIDATION_FAILED", error.message, context);
      return;
    }
    if (error instanceof IdempotencyKeyReusedError) {
      sendError(reply, 422, "IDEMPOTENCY_KEY_REUSED", error.message, context);
      return;
    }
    if (error instanceof NotFoundError) {
      sendError(reply, 404, "NOT_FOUND", error.message, context);
      return;
    }
    if (isDomainError(error)) {
      if (isPreconditionFailure(request, error.code)) {
        sendError(reply, 412, "PRECONDITION_FAILED", error.message, context, { domainCode: error.code });
        return;
      }
      const status = error.code.startsWith("CASE_NOT_") || error.code.startsWith("CLOSURE_") ? 422 : 400;
      sendError(reply, status, status === 422 ? "DOMAIN_RULE_VIOLATION" : "VALIDATION_FAILED", error.message, context, { domainCode: error.code });
      return;
    }
    sendError(reply, 500, "UNAVAILABLE", errorMessage(error), context);
  });

  return app;
}

async function sendIdempotentUpdate(
  request: FastifyRequest,
  reply: FastifyReply,
  idempotencyStore: IdempotencyStore,
  fingerprintSource: unknown,
  update: () => Promise<Parameters<typeof supportCaseResponse>[0]>,
) {
  const response = await withIdempotency(request, idempotencyStore, fingerprintSource, async () => ({
    statusCode: 200,
    body: supportCaseResponse(await update()),
  }));
  return reply.status(response.statusCode).send(response.body);
}

async function withIdempotency(
  request: FastifyRequest,
  idempotencyStore: IdempotencyStore,
  fingerprintSource: unknown,
  operation: () => Promise<{ statusCode: number; body: unknown }>,
): Promise<{ statusCode: number; body: unknown }> {
  const key = headerValue(request.headers["idempotency-key"]);
  if (!key) {
    throw new MissingIdempotencyKeyError();
  }
  if (!isUuidV7(key)) {
    throw new ValidationError("Idempotency-Key header must be a UUID v7", { header: "Idempotency-Key", format: "UUID_V7" });
  }
  return idempotencyStore.execute(key, fingerprintSource, operation);
}

const uuidV7Pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function isUuidV7(value: string): boolean {
  return uuidV7Pattern.test(value);
}

class ValidationError extends Error {
  constructor(message: string, public readonly details: Readonly<Record<string, unknown>> = {}) {
    super(message);
    this.name = "ValidationError";
  }
}

class MissingIdempotencyKeyError extends Error {
  constructor() {
    super("Idempotency-Key header is required for state-changing POST requests");
    this.name = "MissingIdempotencyKeyError";
  }
}

function validateOpenSupportCase(value: unknown): OpenSupportCaseRequest {
  const body = objectBody(value);
  return {
    requesterRef: requiredString(body, "requesterRef"),
    channel: enumValue(body, "channel", channels),
    classification: optionalString(body, "classification"),
    priority: optionalEnumValue(body, "priority", priorities),
    description: requiredString(body, "description"),
    businessReferences: optionalStringRecord(body, "businessReferences"),
  };
}

function validateAttachEvidence(value: unknown): AttachEvidenceRequest {
  const body = objectBody(value);
  return {
    evidenceType: enumValue(body, "evidenceType", evidenceTypes),
    reference: requiredString(body, "reference"),
    summary: requiredString(body, "summary"),
    accessLevel: enumValue(body, "accessLevel", accessLevels),
    attachedBy: requiredString(body, "attachedBy"),
  };
}

function validateClassify(value: unknown): ClassifySupportCaseRequest {
  const body = objectBody(value);
  return { classification: requiredString(body, "classification"), priority: enumValue(body, "priority", priorities) };
}

function validateAssign(value: unknown): AssignSupportCaseRequest {
  const body = objectBody(value);
  return { assignedTo: optionalString(body, "assignedTo"), ownerQueue: requiredString(body, "ownerQueue") };
}

function validateEscalate(value: unknown): EscalateCaseRequest {
  const body = objectBody(value);
  return { targetQueue: requiredString(body, "targetQueue"), reason: requiredString(body, "reason") };
}

function validateResolve(value: unknown): ResolveCaseRequest {
  const body = objectBody(value);
  return { summary: requiredString(body, "summary"), resolutionCode: requiredString(body, "resolutionCode") };
}

function validateClose(value: unknown): CloseCaseRequest {
  const body = objectBody(value);
  return { reason: enumValue(body, "reason", closeReasons) };
}

function validateReopen(value: unknown): ReopenCaseRequest {
  const body = objectBody(value);
  return { reason: requiredString(body, "reason"), requesterRef: requiredString(body, "requesterRef") };
}

function objectBody(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ValidationError("Request body must be a JSON object");
  }
  return value as Record<string, unknown>;
}

function requiredString(body: Record<string, unknown>, field: string): string {
  const value = body[field];
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new ValidationError(`${field} is required`, { field });
  }
  return value;
}

function optionalString(body: Record<string, unknown>, field: string): string | undefined {
  const value = body[field];
  if (value === undefined) {
    return undefined;
  }
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new ValidationError(`${field} must be a non-empty string`, { field });
  }
  return value;
}

function enumValue<T extends readonly string[]>(body: Record<string, unknown>, field: string, allowed: T): T[number] {
  const value = requiredString(body, field);
  if (!allowed.includes(value)) {
    throw new ValidationError(`${field} must be one of ${allowed.join(", ")}`, { field, allowed });
  }
  return value;
}

function optionalEnumValue<T extends readonly string[]>(body: Record<string, unknown>, field: string, allowed: T): T[number] | undefined {
  if (body[field] === undefined) {
    return undefined;
  }
  return enumValue(body, field, allowed);
}

function optionalStringRecord(body: Record<string, unknown>, field: string): Record<string, string> | undefined {
  const value = body[field];
  if (value === undefined) {
    return undefined;
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ValidationError(`${field} must be an object`, { field });
  }
  const result: Record<string, string> = {};
  for (const [key, nested] of Object.entries(value as Record<string, unknown>)) {
    if (typeof nested !== "string") {
      throw new ValidationError(`${field}.${key} must be a string`, { field: `${field}.${key}` });
    }
    result[key] = nested;
  }
  return result;
}

function openSupportCaseResponse(snapshot: Parameters<typeof supportCaseResponse>[0]) {
  return {
    caseId: snapshot.caseId,
    requesterRef: snapshot.requesterRef,
    channel: snapshot.channel,
    priority: snapshot.priority ?? "NORMAL",
    status: apiStatus(snapshot.status),
    createdAt: snapshot.openedAt.toISOString(),
  };
}

function supportCaseResponse(snapshot: {
  caseId: string;
  requesterRef: string;
  channel: string;
  priority?: string;
  status: string;
  classification?: string;
  ownerQueue?: string;
  assignedTo?: string;
  description: string;
  businessReferences: unknown;
  openedAt: Date;
  resolvedAt?: Date;
  resolution?: unknown;
  escalation?: unknown;
  closedAt?: Date;
  closeReason?: string;
  evidence?: readonly unknown[];
}) {
  return {
    caseId: snapshot.caseId,
    requesterRef: snapshot.requesterRef,
    channel: snapshot.channel,
    priority: snapshot.priority ?? "NORMAL",
    status: apiStatus(snapshot.status),
    classification: snapshot.classification,
    ownerQueue: snapshot.ownerQueue,
    assignedTo: snapshot.assignedTo,
    description: snapshot.description,
    businessReferences: snapshot.businessReferences,
    createdAt: snapshot.openedAt.toISOString(),
    resolvedAt: snapshot.resolvedAt?.toISOString(),
    resolution: snapshot.resolution,
    escalation: snapshot.escalation,
    closedAt: snapshot.closedAt?.toISOString(),
    closeReason: snapshot.closeReason,
    evidence: snapshot.evidence,
  };
}

function apiStatus(status: string): "OPENED" | "IN_PROGRESS" | "RESOLVED" | "CLOSED" {
  if (status === "Resolved") {
    return "RESOLVED";
  }
  if (status === "Closed") {
    return "CLOSED";
  }
  if (status === "Opened") {
    return "OPENED";
  }
  return "IN_PROGRESS";
}

function isPreconditionFailure(request: FastifyRequest, domainCode: string): boolean {
  const path = request.url.split("?")[0] ?? request.url;
  if (request.method !== "POST" || !/^\/api\/v1\/support-cases\/[^/]+\/(resolve|close)$/.test(path)) {
    return false;
  }
  return domainCode === "CASE_NOT_RESOLVABLE" || domainCode === "CASE_NOT_CLOSABLE" || domainCode.startsWith("CLOSURE_");
}

function operatorRef(request: FastifyRequest): string {
  return headerValue(request.headers["x-operator-ref"]) ?? `op-${randomUUID()}`;
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
  const existing = requestContexts.get(request);
  if (existing) {
    return existing;
  }
  const requestId = headerValue(request.headers["x-request-id"]) ?? request.id ?? randomUUID();
  const rawCorrelationId = headerValue(request.headers["x-correlation-id"]);
  const correlationId = rawCorrelationId ? canonicalCorrelationId(rawCorrelationId) : newCorrelationId();
  const context = { requestId, correlationId };
  requestContexts.set(request, context);
  return context;
}

type AppRequest = FastifyRequest;
type AppReply = FastifyReply;

const requestContexts = new WeakMap<AppRequest, RequestContext>();

function headerValue(value: string | string[] | undefined): string | undefined {
  const candidate = Array.isArray(value) ? value[0] : value;
  return candidate && candidate.trim().length > 0 ? candidate : undefined;
}

function canonicalCorrelationId(value: string): string {
  return value.startsWith("corr-") ? value : `corr-${value}`;
}

function sendError(
  reply: AppReply,
  statusCode: number,
  code: string,
  message: string,
  context: RequestContext,
  details: Readonly<Record<string, unknown>> = {},
): void {
  const envelope: ErrorEnvelope = {
    code,
    message,
    correlationId: context.correlationId,
    details,
  };
  reply.status(statusCode).send(envelope);
}

function normalizeOptions(options: InstrumentationHooks | AppOptions): AppOptions {
  const candidate = options as AppOptions;
  if (candidate.instrumentation !== undefined || candidate.publisher !== undefined || candidate.application !== undefined || candidate.idempotencyStore !== undefined) {
    return candidate;
  }
  return { instrumentation: options as InstrumentationHooks };
}
