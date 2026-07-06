import { Redis } from "ioredis";
import { canonicalCausationId, canonicalCorrelationId, canonicalEventId, newCommandId, newCorrelationId, newEventId } from "./ids.js";
export class PublishFailed extends Error {
    constructor(message, options) {
        super(message, options);
        this.name = "PublishFailed";
    }
}
export class SubscribeFailed extends Error {
    constructor(message, options) {
        super(message, options);
        this.name = "SubscribeFailed";
    }
}
export class HandlerError extends Error {
    kind;
    constructor(kind, message, options) {
        super(message, options);
        this.kind = kind;
        this.name = "HandlerError";
    }
}
export function createEventEnvelope(input) {
    return Object.freeze({
        eventId: input.eventId ? canonicalEventId(input.eventId) : newEventId(),
        eventType: input.eventType,
        schemaVersion: input.schemaVersion ?? 1,
        producer: input.producer,
        causationId: input.causationId ? canonicalCausationId(input.causationId) : newCommandId(),
        correlationId: input.correlationId ? canonicalCorrelationId(input.correlationId) : newCorrelationId(),
        occurredAt: occurredAt(input.occurredAt),
        payload: Object.freeze(omitUndefined(input.payload)),
    });
}
export async function publishAfterCommit(transaction, publisher, envelopes) {
    const result = await transaction();
    const toPublish = typeof envelopes === "function" ? await envelopes(result) : envelopes;
    for (const envelope of toPublish) {
        await publisher.publish(envelope);
    }
    return result;
}
export function successfulHandling() {
    return { ok: true };
}
export function transientHandling(error) {
    return { ok: false, kind: "transient", error };
}
export function fatalHandling(error) {
    return { ok: false, kind: "fatal", error };
}
export class InMemoryEventPublisher {
    envelopes = [];
    published = this.envelopes;
    failNext = false;
    async publish(envelope) {
        if (this.failNext) {
            this.failNext = false;
            throw new PublishFailed("Simulated publish failure");
        }
        this.envelopes.push(structuredClone(envelope));
    }
    findByProducer(producer) {
        return this.envelopes.filter((envelope) => envelope.producer === producer);
    }
    findByEventType(eventType) {
        return this.envelopes.filter((envelope) => envelope.eventType === eventType);
    }
    reset() {
        this.envelopes.length = 0;
        this.failNext = false;
    }
}
export class DeduplicatingEventHandler {
    delegate;
    consumedEventIds = new Set();
    constructor(delegate) {
        this.delegate = delegate;
    }
    async handle(envelope) {
        if (this.consumedEventIds.has(envelope.eventId)) {
            return successfulHandling();
        }
        const result = await this.delegate(envelope);
        if (result === undefined || handlerSucceeded(result)) {
            this.consumedEventIds.add(envelope.eventId);
        }
        return result ?? successfulHandling();
    }
    hasConsumed(eventId) {
        return this.consumedEventIds.has(eventId);
    }
}
export class ConsumedEventDeduplicator {
    consumedEventIds = new Set();
    async handle(envelope, handler) {
        if (this.consumedEventIds.has(envelope.eventId)) {
            return false;
        }
        await handler(envelope);
        this.consumedEventIds.add(envelope.eventId);
        return true;
    }
}
export class InMemoryEventSubscriber {
    received = [];
    handler;
    processedEventIds = new Set();
    async subscribe(_streams, _group, _consumerName, handler, _signal) {
        this.handler = handler;
    }
    async simulateEvent(envelope) {
        this.received.push(envelope);
        if (this.processedEventIds.has(envelope.eventId)) {
            return "ack";
        }
        if (!this.handler) {
            this.processedEventIds.add(envelope.eventId);
            return "ack";
        }
        const rawResult = await this.handler(envelope);
        const result = rawResult === undefined ? "ack" : normalizeHandlerResult(rawResult);
        if (result === "ack") {
            this.processedEventIds.add(envelope.eventId);
        }
        return result;
    }
    async emit(envelope) {
        if (!this.handler) {
            throw new SubscribeFailed("Subscriber has not been started");
        }
        if (this.processedEventIds.has(envelope.eventId)) {
            return false;
        }
        const rawResult = await this.handler(envelope);
        const result = rawResult === undefined ? "ack" : normalizeHandlerResult(rawResult);
        if (result === "ack") {
            this.processedEventIds.add(envelope.eventId);
            return true;
        }
        if (result === "dlq") {
            return true;
        }
        return false;
    }
    reset() {
        this.received.length = 0;
        this.handler = undefined;
        this.processedEventIds.clear();
    }
}
const READ_BLOCK_MS = 2_000;
const READ_COUNT = 10;
const CLAIM_MIN_IDLE_MS = 60_000;
const MAX_DELIVERIES = 5;
const STREAM_MAXLEN = 100_000;
export class RedisEventPublisher {
    redis;
    constructor(redis) {
        this.redis = redis;
    }
    async publish(envelope) {
        const stream = streamForProducer(envelope.producer);
        const serialized = JSON.stringify(envelope);
        let lastError;
        for (let attempt = 1; attempt <= 3; attempt += 1) {
            try {
                await this.redis.xadd(stream, "MAXLEN", "~", STREAM_MAXLEN, "*", "envelope", serialized);
                return;
            }
            catch (error) {
                lastError = error;
                if (attempt < 3) {
                    await sleep(25 * 2 ** (attempt - 1));
                }
            }
        }
        throw new PublishFailed(`Failed to publish ${envelope.eventType}`, { cause: lastError });
    }
    async close() {
        this.redis.disconnect();
    }
}
export class RedisStreamEventPublisher extends RedisEventPublisher {
    constructor(redis = new Redis(redisUrl(), { lazyConnect: true })) {
        super(redis);
    }
}
export class RedisEventSubscriber {
    redis;
    onLoopFailure;
    options;
    stopped = false;
    consumedEventIds = new Set();
    loops = new Set();
    startedPromise;
    resolveStarted;
    rejectStarted;
    constructor(redis = new Redis(redisUrl(), { lazyConnect: true }), onLoopFailure = defaultLoopFailureHandler, options = {}) {
        this.redis = redis;
        this.onLoopFailure = onLoopFailure;
        this.options = options;
        this.startedPromise = new Promise((resolve, reject) => {
            this.resolveStarted = resolve;
            this.rejectStarted = reject;
        });
    }
    started() {
        return this.startedPromise;
    }
    async subscribe(streams, group, consumerName, handler, signal) {
        try {
            await this.ensureConnected();
            await Promise.all(streams.map((stream) => this.createGroup(stream, group)));
            this.resolveStarted();
            this.startLoop(this.poll(streams, group, consumerName, handler, signal));
            this.startLoop(this.recover(streams, group, consumerName, handler, signal));
        }
        catch (error) {
            this.stopped = true;
            this.rejectStarted(error);
            throw new SubscribeFailed("Failed to subscribe to event streams", { cause: error });
        }
    }
    async stop() {
        this.stopped = true;
        this.redis.disconnect();
        await Promise.allSettled([...this.loops]);
    }
    async close() {
        await this.stop();
    }
    startLoop(loop) {
        this.loops.add(loop);
        loop.catch((error) => {
            if (!this.stopped) {
                this.onLoopFailure(new SubscribeFailed("Redis subscriber background loop failed", { cause: error }));
            }
        }).finally(() => this.loops.delete(loop));
    }
    async poll(streams, group, consumerName, handler, signal) {
        while (!this.stopped && !signal?.aborted) {
            const redisWithRead = this.redis;
            const read = redisWithRead.call?.bind(this.redis) ?? redisWithRead.xreadgroup?.bind(this.redis);
            if (!read) {
                throw new TypeError("Redis client does not support XREADGROUP");
            }
            try {
                const messages = await read("XREADGROUP", "GROUP", group, consumerName, "BLOCK", READ_BLOCK_MS, "COUNT", READ_COUNT, "STREAMS", ...streams, ...streams.map(() => ">"));
                await this.processMessages(messages, group, handler);
            }
            catch (error) {
                // Non-persistent redis loses consumer groups on restart; recreate then back off so a dead connection never hot-spins.
                if (String(error).includes("NOGROUP")) {
                    await Promise.all(streams.map((stream) => this.createGroup(stream, group)));
                }
                if (!isRecoverableRedisReadError(error)) {
                    throw error;
                }
                await sleep(1_000);
            }
        }
    }
    async recover(streams, group, consumerName, handler, signal) {
        while (!this.stopped && !signal?.aborted) {
            await sleepUntil(() => this.stopped || signal?.aborted === true, CLAIM_MIN_IDLE_MS);
            if (this.stopped || signal?.aborted) {
                return;
            }
            for (const stream of streams) {
                await this.claimAndProcess(stream, group, consumerName, handler);
            }
        }
    }
    async processMessages(messages, group, handler) {
        for (const [stream, entries] of messages ?? []) {
            for (const entry of entries) {
                await this.processEntry(stream, group, entry, handler);
            }
        }
    }
    async processEntry(stream, group, entry, handler) {
        const [entryId, fields] = entry;
        const envelopeJson = fieldValue(fields, "envelope");
        if (!envelopeJson) {
            await this.deadLetterAndAck(stream, group, entry);
            return;
        }
        let envelope;
        try {
            envelope = JSON.parse(envelopeJson);
        }
        catch {
            await this.deadLetterAndAck(stream, group, entry);
            return;
        }
        if (this.consumedEventIds.has(envelope.eventId)) {
            await this.redis.xack(stream, group, entryId);
            return;
        }
        let result;
        try {
            const rawResult = await handler(envelope);
            result = rawResult === undefined ? "ack" : normalizeHandlerResult(rawResult);
        }
        catch (error) {
            console.error(sanitizedErrorForLog(error));
            result = error instanceof HandlerError
                ? (error.kind === "fatal" ? "dlq" : "retry")
                : (this.options.thrownHandlerErrors === "retry" ? "retry" : "dlq");
        }
        if (result === "ack") {
            this.consumedEventIds.add(envelope.eventId);
            await this.redis.xack(stream, group, entryId);
            return;
        }
        if (result === "dlq") {
            await this.deadLetterAndAck(stream, group, entry);
        }
    }
    async claimAndProcess(stream, group, consumerName, handler) {
        const claimed = await this.redis.xautoclaim(stream, group, consumerName, CLAIM_MIN_IDLE_MS, "0", "COUNT", 100);
        const entries = (Array.isArray(claimed[1]) ? claimed[1] : []);
        const deliveryCounts = await this.deliveryCounts(stream, group, entries.map(([entryId]) => entryId));
        for (const entry of entries) {
            if ((deliveryCounts.get(entry[0]) ?? 1) >= MAX_DELIVERIES) {
                await this.deadLetterAndAck(stream, group, entry);
            }
            else {
                await this.processEntry(stream, group, entry, handler);
            }
        }
    }
    async deliveryCounts(stream, group, entryIds) {
        const counts = new Map();
        await Promise.all(entryIds.map(async (entryId) => {
            const pending = await this.redis.xpending(stream, group, entryId, entryId, 1);
            const row = pending[0];
            const deliveries = row?.[3];
            if (typeof deliveries === "number") {
                counts.set(entryId, deliveries);
            }
        }));
        return counts;
    }
    async deadLetterAndAck(stream, group, entry) {
        const envelopeJson = fieldValue(entry[1], "envelope") ?? JSON.stringify({});
        await this.redis.xadd(dlqForStream(stream), "MAXLEN", "~", STREAM_MAXLEN, "*", "envelope", envelopeJson);
        await this.redis.xack(stream, group, entry[0]);
    }
    async createGroup(stream, group) {
        try {
            await this.redis.xgroup("CREATE", stream, group, "$", "MKSTREAM");
        }
        catch (error) {
            if (!String(error).includes("BUSYGROUP")) {
                throw error;
            }
        }
    }
    async ensureConnected() {
        const status = this.redis.status;
        if (status === "wait") {
            await this.redis.connect();
        }
    }
}
export class RedisStreamEventSubscriber extends RedisEventSubscriber {
}
export async function createRedisMessagingAdapters(url = redisUrl()) {
    const publisherRedis = new Redis(url, { lazyConnect: true });
    const subscriberRedis = new Redis(url, { lazyConnect: true });
    await Promise.all([publisherRedis.connect(), subscriberRedis.connect()]);
    return {
        publisher: new RedisEventPublisher(publisherRedis),
        subscriber: new RedisEventSubscriber(subscriberRedis),
        close: async () => {
            await Promise.allSettled([publisherRedis.quit(), subscriberRedis.quit()]);
        },
    };
}
export function streamForProducer(producer) {
    return `events:${producer}`;
}
export const streamKey = streamForProducer;
export function dlqForStream(stream) {
    return `${stream}:dlq`;
}
export function dlqStreamKey(context) {
    return `events:${context}:dlq`;
}
export function redisUrl() {
    return process.env.REDIS_URL ?? "redis://localhost:6379";
}
function isRecoverableRedisReadError(error) {
    const message = String(error);
    return message.includes("NOGROUP") || message.includes("Connection is closed") || message.includes("Connection is not established") || message.includes("ECONNREFUSED") || message.includes("ETIMEDOUT") || message.includes("READONLY") || message.includes("LOADING");
}
function sanitizedErrorForLog(error) {
    if (error instanceof Error) {
        return { name: error.name || "Error", message: error.message || "Handler failed" };
    }
    return { name: typeof error, message: "Handler failed" };
}
function normalizeHandlerResult(result) {
    if (result === "ack" || result === "retry" || result === "dlq") {
        return result;
    }
    if (result.ok) {
        return "ack";
    }
    const kind = result.kind ?? result.errorType;
    return kind === "fatal" ? "dlq" : "retry";
}
function handlerSucceeded(result) {
    return normalizeHandlerResult(result) === "ack";
}
function occurredAt(value) {
    if (value instanceof Date) {
        return value.toISOString();
    }
    if (typeof value === "string") {
        return new Date(value).toISOString();
    }
    return new Date().toISOString();
}
function omitUndefined(values) {
    const payload = {};
    for (const [key, value] of Object.entries(values)) {
        if (value !== undefined) {
            payload[key] = value;
        }
    }
    return payload;
}
function fieldValue(fields, name) {
    for (let index = 0; index < fields.length; index += 2) {
        if (fields[index] === name) {
            return fields[index + 1];
        }
    }
    return undefined;
}
function sleep(milliseconds) {
    return new Promise((resolve) => {
        const timer = setTimeout(resolve, milliseconds);
        timer.unref?.();
    });
}
async function sleepUntil(stopped, milliseconds) {
    const deadline = Date.now() + milliseconds;
    while (!stopped() && Date.now() < deadline) {
        await sleep(Math.min(100, deadline - Date.now()));
    }
}
function defaultLoopFailureHandler(error) {
    console.error(sanitizedErrorForLog(error));
}
