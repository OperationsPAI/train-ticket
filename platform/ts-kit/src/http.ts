import { createHash, randomUUID } from "node:crypto";

import { isUuidV7, newCorrelationId, canonicalCorrelationId } from "./ids.js";

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

export type IdempotencyRecord = Readonly<{
  fingerprint: string;
  statusCode: number;
  body: unknown;
}>;

export interface IdempotencyStore {
  get(key: string): IdempotencyRecord | undefined | Promise<IdempotencyRecord | undefined>;
  set(key: string, record: IdempotencyRecord): IdempotencyRecord | void | Promise<IdempotencyRecord | void>;
}

export class InMemoryIdempotencyStore implements IdempotencyStore {
  private readonly records = new Map<string, IdempotencyRecord>();

  get(key: string): IdempotencyRecord | undefined {
    return this.records.get(key);
  }

  set(key: string, record: IdempotencyRecord): void {
    this.records.set(key, record);
  }

  save(key: string, record: { bodyHash?: string; statusCode: number; payload?: unknown; body?: unknown; fingerprint?: string }): void {
    this.set(key, {
      fingerprint: record.fingerprint ?? record.bodyHash ?? "",
      statusCode: record.statusCode,
      body: record.body ?? record.payload,
    });
  }

  clear(): void {
    this.records.clear();
  }
}

export type HeaderBag = Record<string, string | string[] | undefined>;

export function headerValue(value: string | string[] | undefined): string | undefined {
  const candidate = Array.isArray(value) ? value[0] : value;
  return candidate && candidate.trim().length > 0 ? candidate : undefined;
}

export function requestContext(input: { headers: HeaderBag; id?: string }): RequestContext {
  const requestId = headerValue(input.headers["x-request-id"]) ?? input.id ?? randomUUID();
  const correlationId = canonicalHttpCorrelationId(headerValue(input.headers["x-correlation-id"]));
  return { requestId, correlationId };
}

export function canonicalHttpCorrelationId(value: string | undefined): string {
  if (!value) {
    return newCorrelationId();
  }
  try {
    return canonicalCorrelationId(value);
  } catch {
    return newCorrelationId();
  }
}

export function errorBody(code: string, message: string, context: RequestContext, details: Readonly<Record<string, unknown>> = {}): ErrorEnvelope {
  return { code, message, correlationId: context.correlationId, details };
}

export function sendError(reply: { status: (statusCode: number) => { send: (body: ErrorEnvelope) => unknown } }, statusCode: number, code: string, message: string, context: RequestContext, details: Readonly<Record<string, unknown>> = {}): void {
  reply.status(statusCode).send(errorBody(code, message, context, details));
}

export function isFrameworkValidationError(error: unknown): boolean {
  return typeof error === "object" && error !== null && (("statusCode" in error && (error as { statusCode?: unknown }).statusCode === 400) || "validation" in error);
}

export function errorMessage(error: unknown): string {
  return error instanceof Error && error.message.length > 0 ? error.message : "Internal server error";
}

export function requestFingerprint(method: string, path: string, body: unknown): string {
  return createHash("sha256").update(JSON.stringify({ method, path, body: normalizeJson(body ?? null) })).digest("hex");
}

export const fingerprintRequest = requestFingerprint;

export async function handleIdempotency<T>(options: {
  key: string | undefined;
  store: IdempotencyStore;
  fingerprint: string;
  context: RequestContext;
  reply: { status: (statusCode: number) => { send: (body: unknown) => unknown } };
  operation: () => Promise<Readonly<{ statusCode: number; body: T }>>;
}): Promise<void> {
  if (!options.key) {
    options.reply.status(400).send(errorBody("VALIDATION_FAILED", "Idempotency-Key header is required", options.context, { field: "Idempotency-Key", header: "Idempotency-Key" }));
    return;
  }
  if (!isUuidV7(options.key)) {
    options.reply.status(400).send(errorBody("VALIDATION_FAILED", "Idempotency-Key must be a UUID v7", options.context, { field: "Idempotency-Key", header: "Idempotency-Key", format: "UUID_V7" }));
    return;
  }

  const existing = await options.store.get(options.key);
  if (existing) {
    if (existing.fingerprint !== options.fingerprint) {
      options.reply.status(422).send(errorBody("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was reused with a different request body", options.context));
      return;
    }
    options.reply.status(existing.statusCode).send(existing.body);
    return;
  }

  const result = await options.operation();
  const visible = await options.store.set(options.key, { fingerprint: options.fingerprint, statusCode: result.statusCode, body: result.body });
  if (visible) {
    if (visible.fingerprint !== options.fingerprint) {
      options.reply.status(422).send(errorBody("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was reused with a different request body", options.context));
      return;
    }
    options.reply.status(visible.statusCode).send(visible.body);
    return;
  }
  options.reply.status(result.statusCode).send(result.body);
}

export async function handleIdempotentPost<T>(
  request: { headers: HeaderBag; id?: string; method: string; url: string; body?: unknown },
  reply: { status: (statusCode: number) => { send: (body: unknown) => unknown } },
  store: IdempotencyStore,
  handler: () => Promise<Readonly<{ statusCode: number; payload: T }>>,
): Promise<T | ErrorEnvelope | undefined> {
  let sentBody: unknown;
  const capturingReply = {
    status: (statusCode: number) => ({
      send: (body: unknown) => {
        sentBody = body;
        return reply.status(statusCode).send(body);
      },
    }),
  };
  await handleIdempotency({
    key: headerValue(request.headers["idempotency-key"]),
    store,
    fingerprint: requestFingerprint(request.method, request.url.split("?")[0] ?? request.url, request.body ?? null),
    context: requestContext(request),
    reply: capturingReply,
    operation: async () => {
      const result = await handler();
      return { statusCode: result.statusCode, body: result.payload };
    },
  });
  return sentBody as T | ErrorEnvelope | undefined;
}

function normalizeJson(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(normalizeJson);
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).sort(([left], [right]) => left.localeCompare(right)).map(([key, nested]) => [key, normalizeJson(nested)]));
  }
  return value;
}
