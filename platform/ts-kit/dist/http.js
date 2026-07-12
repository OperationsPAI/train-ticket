import { createHash, randomUUID } from "node:crypto";
import { isUuidV7, newCorrelationId, canonicalCorrelationId } from "./ids.js";
export class InMemoryIdempotencyStore {
    records = new Map();
    get(key) {
        return this.records.get(key);
    }
    set(key, record) {
        this.records.set(key, record);
    }
    save(key, record) {
        this.set(key, {
            fingerprint: record.fingerprint ?? record.bodyHash ?? "",
            statusCode: record.statusCode,
            body: record.body ?? record.payload,
        });
    }
    clear() {
        this.records.clear();
    }
}
export function headerValue(value) {
    const candidate = Array.isArray(value) ? value[0] : value;
    return candidate && candidate.trim().length > 0 ? candidate : undefined;
}
export function requestContext(input) {
    const requestId = headerValue(input.headers["x-request-id"]) ?? input.id ?? randomUUID();
    const correlationId = canonicalHttpCorrelationId(headerValue(input.headers["x-correlation-id"]));
    return { requestId, correlationId };
}
export function canonicalHttpCorrelationId(value) {
    if (!value) {
        return newCorrelationId();
    }
    try {
        return canonicalCorrelationId(value);
    }
    catch {
        return newCorrelationId();
    }
}
export function errorBody(code, message, context, details = {}) {
    return { code, message, correlationId: context.correlationId, details };
}
export function sendError(reply, statusCode, code, message, context, details = {}) {
    reply.status(statusCode).send(errorBody(code, message, context, details));
}
export function isFrameworkValidationError(error) {
    return typeof error === "object" && error !== null && (("statusCode" in error && error.statusCode === 400) || "validation" in error);
}
export function errorMessage(error) {
    return error instanceof Error && error.message.length > 0 ? error.message : "Internal server error";
}
export function requestFingerprint(method, path, body) {
    return createHash("sha256").update(JSON.stringify({ method, path, body: normalizeJson(body ?? null) })).digest("hex");
}
export const fingerprintRequest = requestFingerprint;
export async function handleIdempotency(options) {
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
export async function handleIdempotentPost(request, reply, store, handler) {
    let sentBody;
    const capturingReply = {
        status: (statusCode) => ({
            send: (body) => {
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
    return sentBody;
}
function normalizeJson(value) {
    if (Array.isArray(value)) {
        return value.map(normalizeJson);
    }
    if (value && typeof value === "object") {
        return Object.fromEntries(Object.entries(value).sort(([left], [right]) => left.localeCompare(right)).map(([key, nested]) => [key, normalizeJson(nested)]));
    }
    return value;
}
