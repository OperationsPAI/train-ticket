import { Redis } from "ioredis";
import { activeTraceContext, endSpan, endSpanWithError, markSpanError, remoteTraceContext, startConsumerSpan } from "./observability.js";
import { registerLivenessComponent } from "./liveness.js";
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
    const traceContext = activeTraceContext();
    return Object.freeze({
        eventId: input.eventId ? canonicalEventId(input.eventId) : newEventId(),
        eventType: input.eventType,
        schemaVersion: input.schemaVersion ?? 1,
        producer: input.producer,
        causationId: input.causationId ? canonicalCausationId(input.causationId) : newCommandId(),
        correlationId: input.correlationId ? canonicalCorrelationId(input.correlationId) : newCorrelationId(),
        occurredAt: occurredAt(input.occurredAt),
        payload: Object.freeze(omitUndefined(input.payload)),
        ...traceContext,
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
        const rawResult = await handleWithConsumerSpan(this.handler, envelope, "in-memory", "in-memory");
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
        const rawResult = await handleWithConsumerSpan(this.handler, envelope, "in-memory", "in-memory");
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
const READ_BLOCK_MS = parseInt(process.env.CONSUMER_BLOCK_MS ?? "100", 10);
const READ_COUNT = parseInt(process.env.CONSUMER_BATCH_COUNT ?? "100", 10);
const CLAIM_MIN_IDLE_MS = 60_000;
const DEAD_CONSUMER_IDLE_MS = 5 * 60 * 1_000;
const MAX_DELIVERIES = 5;
export const STREAM_MAXLEN_ENV = "EVENT_STREAM_MAXLEN";
export const DEFAULT_STREAM_MAXLEN = 10_000;
/**
 * Resolves the per-stream entry cap. A blank, non-numeric or non-positive value falls
 * back to the default rather than throwing or trimming to zero: a misconfigured cap that
 * silently discarded every published event would be the worst outcome here.
 */
export function streamMaxLen(configured) {
    const trimmed = configured?.trim();
    if (!trimmed) {
        return DEFAULT_STREAM_MAXLEN;
    }
    const parsed = Number(trimmed);
    if (Number.isInteger(parsed) && parsed > 0) {
        return parsed;
    }
    console.warn(`${STREAM_MAXLEN_ENV}=${configured} is not a positive integer; using default ${DEFAULT_STREAM_MAXLEN}`);
    return DEFAULT_STREAM_MAXLEN;
}
/**
 * Cap on entries kept per stream. Redis streams are never read destructively, so without
 * a cap every published event stays resident forever and eventually exhausts the Redis
 * memory limit. Always applied as `MAXLEN ~` so XADD stays O(1).
 */
const STREAM_MAXLEN = streamMaxLen(process.env[STREAM_MAXLEN_ENV]);
export function configuredStreamMaxLen() {
    return STREAM_MAXLEN;
}
/**
 * Reconnect backoff for every Redis client in this codebase.
 *
 * ioredis' built-in default is `Math.min(times * 50, 2000)`, which does retry
 * forever -- but it is paired with `maxRetriesPerRequest: 20`, which is the
 * real problem: after 20 reconnect attempts ioredis flushes the command queue
 * and rejects every in-flight command with MaxRetriesPerRequestError. A
 * blocking XREADGROUP sitting in that queue is rejected, and if the rejection
 * is not handled the consumer loop's promise dies for good.
 *
 * Returning a number here unconditionally means ioredis NEVER gives up
 * reconnecting (`closeHandler` only stops when retryStrategy returns a
 * non-number).
 */
export function redisRetryStrategy(attempt) {
    return Math.min(100 * 2 ** Math.min(attempt - 1, 8), REDIS_MAX_RECONNECT_DELAY_MS);
}
const REDIS_MAX_RECONNECT_DELAY_MS = 30_000;
/**
 * Baseline options for every Redis client.
 *
 * - `retryStrategy`: capped exponential backoff, retried indefinitely.
 * - `maxRetriesPerRequest: null`: never flush the command queue with
 *   MaxRetriesPerRequestError. Commands wait for the reconnect instead of
 *   being rejected into a promise nobody is watching.
 * - `enableOfflineQueue`: queue commands issued while disconnected so they
 *   run after the reconnect rather than throwing "Connection is closed".
 * - `enableReadyCheck`: wait for Redis to finish LOADING before sending
 *   commands, so a restarted Redis does not get commands it will reject.
 */
export function redisClientOptions(overrides = {}) {
    return {
        lazyConnect: true,
        retryStrategy: redisRetryStrategy,
        maxRetriesPerRequest: null,
        enableOfflineQueue: true,
        enableReadyCheck: true,
        connectTimeout: 10_000,
        ...overrides,
    };
}
/**
 * Create a Redis client that reconnects indefinitely and never emits an
 * "Unhandled error event".
 *
 * An ioredis client with no `error` listener routes connection errors through
 * `silentEmit`, which just does `console.error("[ioredis] Unhandled error
 * event:", ...)`. That is exactly the last line the `account` pod logged on
 * 2026-09-06 before going silent for 17 hours. Attaching a listener keeps the
 * error observable and, more importantly, keeps it from being the only trace
 * of a client that has stopped working.
 *
 * The returned client feeds a liveness component so a permanently
 * disconnected client eventually fails `/healthz` and kubelet restarts us.
 */
export function createRedisClient(url = redisUrl(), overrides = {}, name = "redis") {
    const client = new Redis(url, redisClientOptions(overrides));
    redisLivenessComponents.set(client, attachRedisLifecycleLogging(client, name));
    return client;
}
const redisLivenessComponents = new WeakMap();
/** The liveness component tracking this client, if it was built by createRedisClient. */
export function redisClientLiveness(client) {
    return redisLivenessComponents.get(client);
}
export function attachRedisLifecycleLogging(client, name) {
    const component = registerLivenessComponent(`${name}.connection`);
    const emitter = client;
    // Without this listener ioredis' silentEmit() falls back to
    // console.error("[ioredis] Unhandled error event: ...") and the error is
    // otherwise invisible. With it, connection errors are logged structurally
    // and never escape as unhandled events.
    emitter.on("error", (error) => {
        component.markUnhealthy(`redis error: ${sanitizedErrorForLog(error).message}`);
        console.warn({
            name,
            message: "redis connection error; ioredis will keep reconnecting",
            error: sanitizedErrorForLog(error),
        });
    });
    emitter.on("close", () => {
        component.markUnhealthy("redis connection closed");
    });
    emitter.on("end", () => {
        component.markUnhealthy("redis connection ended");
    });
    emitter.on("reconnecting", (delay) => {
        component.markUnhealthy("redis reconnecting");
        console.info({ name, message: "redis reconnecting", delayMs: typeof delay === "number" ? delay : undefined });
    });
    emitter.on("ready", () => {
        component.markHealthy();
        console.info({ name, message: "redis connection ready" });
    });
    return component;
}
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
    constructor(redis = createRedisClient(redisUrl(), {}, "publisher")) {
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
    consumerLiveness;
    /**
     * Set whenever the Redis connection drops. A restarted Redis has no
     * consumer groups (this system treats Redis as transport only and replays
     * from the Postgres outbox), so the groups must be re-created before the
     * next read instead of erroring on NOGROUP forever.
     */
    groupsNeedRecreate = false;
    constructor(redis = createRedisClient(redisUrl(), {}, "subscriber"), onLoopFailure = defaultLoopFailureHandler, options = {}) {
        this.redis = redis;
        this.onLoopFailure = onLoopFailure;
        this.options = options;
        this.startedPromise = new Promise((resolve, reject) => {
            this.resolveStarted = resolve;
            this.rejectStarted = reject;
        });
        this.watchConnection();
    }
    started() {
        return this.startedPromise;
    }
    async subscribe(streams, group, consumerName, handler, signal) {
        try {
            await this.ensureConnected();
            await Promise.all(streams.map((stream) => this.createGroup(stream, group)));
            await this.pruneDeadConsumers(streams, group, consumerName);
            this.consumerLiveness ??= registerLivenessComponent(`redis-consumer.${group}`);
            this.consumerLiveness.markHealthy();
            this.resolveStarted();
            this.superviseLoop("poll", group, () => this.poll(streams, group, consumerName, handler, signal), signal);
            this.superviseLoop("recover", group, () => this.recover(streams, group, consumerName, handler, signal), signal);
        }
        catch (error) {
            this.stopped = true;
            this.rejectStarted(error);
            throw new SubscribeFailed("Failed to subscribe to event streams", { cause: error });
        }
    }
    async stop() {
        this.stopped = true;
        this.consumerLiveness?.dispose();
        this.redis.disconnect();
        await Promise.allSettled([...this.loops]);
    }
    async close() {
        await this.stop();
    }
    /**
     * Track connection lifecycle so that (a) a reconnect triggers consumer
     * group re-creation and (b) a permanently disconnected client is visible
     * to the liveness probe.
     */
    watchConnection() {
        const emitter = this.redis;
        if (typeof emitter.on !== "function") {
            return;
        }
        emitter.on("close", () => {
            this.groupsNeedRecreate = true;
        });
        emitter.on("ready", () => {
            // Reconnected: the server may be a freshly restarted, empty Redis.
            this.groupsNeedRecreate = true;
        });
    }
    /**
     * Run a background loop and RESTART it if it ever rejects.
     *
     * The previous implementation only logged:
     *
     *   loop.catch((error) => { if (!this.stopped) this.onLoopFailure(...) })
     *
     * which let a single rejection permanently end consumption while the
     * process stayed up and `/healthz` kept returning 200. A supervised loop
     * turns "the consumer died" into "the consumer restarts with backoff".
     */
    superviseLoop(name, group, factory, signal) {
        const supervised = (async () => {
            let restarts = 0;
            while (!this.stopped && !signal?.aborted) {
                try {
                    await factory();
                    return;
                }
                catch (error) {
                    if (this.stopped || signal?.aborted) {
                        return;
                    }
                    restarts += 1;
                    this.consumerLiveness?.markUnhealthy(`${name} loop crashed: ${sanitizedErrorForLog(error).message}`);
                    this.onLoopFailure(new SubscribeFailed("Redis subscriber background loop failed", { cause: error }));
                    console.warn({
                        service: group,
                        loop: name,
                        restarts,
                        error: sanitizedErrorForLog(error),
                        message: "subscriber loop crashed; restarting after backoff",
                    });
                    await sleep(loopRestartDelayMs(restarts));
                }
            }
        })();
        this.loops.add(supervised);
        supervised.finally(() => this.loops.delete(supervised)).catch(() => { });
    }
    async poll(streams, group, consumerName, handler, signal) {
        let consecutiveFailures = 0;
        while (!this.stopped && !signal?.aborted) {
            const redisWithRead = this.redis;
            const read = redisWithRead.call?.bind(this.redis) ?? redisWithRead.xreadgroup?.bind(this.redis);
            if (!read) {
                throw new TypeError("Redis client does not support XREADGROUP");
            }
            try {
                // A reconnect may have landed us on a freshly restarted, empty Redis
                // with no streams and no consumer groups. Re-create them before
                // reading so we never spin on NOGROUP forever.
                if (this.groupsNeedRecreate) {
                    this.groupsNeedRecreate = false;
                    await Promise.all(streams.map((stream) => this.createGroup(stream, group)));
                }
                const messages = await read("XREADGROUP", "GROUP", group, consumerName, "BLOCK", READ_BLOCK_MS, "COUNT", READ_COUNT, "STREAMS", ...streams, ...streams.map(() => ">"));
                consecutiveFailures = 0;
                this.consumerLiveness?.markHealthy();
                await this.processMessages(messages, group, consumerName, handler);
            }
            catch (error) {
                consecutiveFailures += 1;
                // Non-persistent redis loses consumer groups on restart; recreate then back off so a dead connection never hot-spins.
                console.warn({
                    service: group,
                    stream: streams.join(","),
                    eventId: "unknown",
                    deliveries: 0,
                    consecutiveFailures,
                    error: sanitizedErrorForLog(error),
                    message: "poll read failed; recreating group if missing and backing off",
                });
                // The consumer is not consuming right now. Liveness only fails if
                // this persists past the grace period, so blips never flap the pod.
                this.consumerLiveness?.markUnhealthy(`poll failing: ${sanitizedErrorForLog(error).message}`);
                if (isMissingGroupError(error)) {
                    try {
                        await Promise.all(streams.map((stream) => this.createGroup(stream, group)));
                    }
                    catch {
                        // Redis may still be down; retry the re-create next iteration.
                        this.groupsNeedRecreate = true;
                    }
                }
                if (!isRecoverableRedisReadError(error)) {
                    // Unknown errors are reported, never fatal: an unlisted driver
                    // message must not silently kill the subscriber loop.
                    this.onLoopFailure(error);
                }
                await sleep(pollBackoffMs(consecutiveFailures));
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
                try {
                    await this.claimAndProcess(stream, group, consumerName, handler);
                }
                catch (error) {
                    // One stream's XAUTOCLAIM failure must not kill the whole
                    // recovery loop; log with context and keep recovering.
                    console.warn({
                        service: group,
                        stream,
                        eventId: "unknown",
                        deliveries: 0,
                        error: sanitizedErrorForLog(error),
                        message: "pending-entry recovery failed; will retry next cycle",
                    });
                }
            }
        }
    }
    async processMessages(messages, group, consumerName, handler) {
        for (const [stream, entries] of messages ?? []) {
            await this.processEntries(stream, group, consumerName, entries, handler);
        }
    }
    async processEntries(stream, group, consumerName, entries, handler) {
        if (!handler.handleBatch || entries.length <= 1) {
            for (const entry of entries) {
                await this.processEntry(stream, group, consumerName, entry, handler);
            }
            return;
        }
        const batch = [];
        for (const entry of entries) {
            const parsed = await this.parseBatchEntry(stream, group, consumerName, entry, 1);
            if (parsed) {
                batch.push(parsed);
            }
        }
        if (batch.length === 0) {
            return;
        }
        let batchResult;
        try {
            batchResult = await handleBatchWithConsumerSpans(handler.handleBatch, batch.map(({ envelope }) => envelope), stream, group);
        }
        catch (error) {
            for (const item of batch) {
                const result = error instanceof HandlerError
                    ? (error.kind === "fatal" ? "dlq" : "retry")
                    : (this.options.thrownHandlerErrors === "retry" ? "retry" : "dlq");
                await this.finishEntry(stream, group, consumerName, item.entry, item.envelope, item.attempts, result, error);
            }
            return;
        }
        const results = Array.isArray(batchResult) ? batchResult : batch.map(() => batchResult);
        if (results.length !== batch.length) {
            const error = new HandlerError("fatal", `Batch handler returned ${results.length} results for ${batch.length} events`);
            for (const item of batch) {
                await this.finishEntry(stream, group, consumerName, item.entry, item.envelope, item.attempts, "dlq", error);
            }
            return;
        }
        for (const [index, item] of batch.entries()) {
            const rawResult = results[index];
            await this.finishEntry(stream, group, consumerName, item.entry, item.envelope, item.attempts, rawResult === undefined ? "ack" : normalizeHandlerResult(rawResult), failureReasonFromHandlerResult(rawResult));
        }
    }
    async parseBatchEntry(stream, group, consumerName, entry, deliveryAttempts) {
        const [entryId, fields] = entry;
        const attempts = Math.max(1, deliveryAttempts);
        const envelopeJson = fieldValue(fields, "envelope");
        if (!envelopeJson) {
            await this.deadLetterAndAck(stream, group, consumerName, entry, "MissingEnvelope", attempts);
            return undefined;
        }
        let envelope;
        try {
            envelope = deserializeEventEnvelope(envelopeJson);
        }
        catch (error) {
            await this.deadLetterAndAck(stream, group, consumerName, entry, error, attempts);
            return undefined;
        }
        if (this.consumedEventIds.has(envelope.eventId)) {
            console.warn({
                service: group,
                stream,
                eventId: envelope.eventId,
                deliveries: attempts,
                message: "duplicate event already processed; acking without handler",
            });
            await this.redis.xack(stream, group, entryId);
            return undefined;
        }
        return { entry, envelope, attempts };
    }
    async processEntry(stream, group, consumerName, entry, handler, deliveryAttempts = 1) {
        const [entryId, fields] = entry;
        const attempts = Math.max(1, deliveryAttempts);
        const envelopeJson = fieldValue(fields, "envelope");
        if (!envelopeJson) {
            await this.deadLetterAndAck(stream, group, consumerName, entry, "MissingEnvelope", attempts);
            return;
        }
        let envelope;
        try {
            envelope = deserializeEventEnvelope(envelopeJson);
        }
        catch (error) {
            await this.deadLetterAndAck(stream, group, consumerName, entry, error, attempts);
            return;
        }
        if (this.consumedEventIds.has(envelope.eventId)) {
            console.warn({
                service: group,
                stream,
                eventId: envelope.eventId,
                deliveries: attempts,
                message: "duplicate event already processed; acking without handler",
            });
            await this.redis.xack(stream, group, entryId);
            return;
        }
        let result;
        let failureReason = "HandlerResult.dlq";
        try {
            const rawResult = await handleWithConsumerSpan(handler, envelope, stream, group);
            result = rawResult === undefined ? "ack" : normalizeHandlerResult(rawResult);
            failureReason = failureReasonFromHandlerResult(rawResult);
        }
        catch (error) {
            result = error instanceof HandlerError
                ? (error.kind === "fatal" ? "dlq" : "retry")
                : (this.options.thrownHandlerErrors === "retry" ? "retry" : "dlq");
            failureReason = error;
            if (result === "dlq") {
                console.error(sanitizedErrorForLog(error));
            }
        }
        await this.finishEntry(stream, group, consumerName, entry, envelope, attempts, result, failureReason);
    }
    async finishEntry(stream, group, consumerName, entry, envelope, attempts, result, failureReason) {
        if (result === "ack") {
            this.consumedEventIds.add(envelope.eventId);
            await this.redis.xack(stream, group, entry[0]);
            return;
        }
        if (result === "dlq") {
            await this.deadLetterAndAck(stream, group, consumerName, entry, failureReason, attempts);
            return;
        }
        console.warn({
            service: group,
            stream,
            eventId: envelope.eventId,
            deliveries: attempts,
            reason: failureReasonForLog(failureReason),
            message: "handler transient failure; message stays pending for retry",
        });
    }
    async claimAndProcess(stream, group, consumerName, handler) {
        const claimed = await this.redis.xautoclaim(stream, group, consumerName, CLAIM_MIN_IDLE_MS, "0", "COUNT", 100);
        const entries = (Array.isArray(claimed[1]) ? claimed[1] : []);
        const deliveryCounts = await this.deliveryCounts(stream, group, entries.map(([entryId]) => entryId));
        for (const entry of entries) {
            if ((deliveryCounts.get(entry[0]) ?? 1) >= MAX_DELIVERIES) {
                await this.deadLetterAndAck(stream, group, consumerName, entry, "MaxDeliveries", deliveryCounts.get(entry[0]) ?? MAX_DELIVERIES);
            }
            else {
                await this.processEntry(stream, group, consumerName, entry, handler, deliveryCounts.get(entry[0]) ?? 1);
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
    async deadLetterAndAck(stream, group, consumerName, entry, reason, attempts) {
        const envelopeJson = fieldValue(entry[1], "envelope") ?? JSON.stringify({});
        const failureReason = truncateFailureReason(reason);
        const safeAttempts = Math.max(1, attempts);
        const deadLetteredAt = new Date().toISOString();
        console.warn({
            service: group,
            stream,
            eventId: eventIdForLog(envelopeJson),
            deliveries: safeAttempts,
            consumerGroup: group,
            failureReason,
            attempts: safeAttempts,
            deadLetteredAt,
            message: "moving message to DLQ",
        });
        await this.redis.xadd(dlqForStream(stream), "MAXLEN", "~", STREAM_MAXLEN, "*", "envelope", envelopeJson, "consumerGroup", group, "consumerName", consumerName, "failureReason", failureReason, "attempts", String(safeAttempts), "deadLetteredAt", deadLetteredAt);
        await this.redis.xack(stream, group, entry[0]);
    }
    async createGroup(stream, group) {
        try {
            // MKSTREAM re-creates the stream too: after a Redis restart neither the
            // stream nor the group exists, and "$" is the right start position
            // because Redis is transport-only here (unpublished work is replayed
            // from the Postgres outbox).
            await this.redis.xgroup("CREATE", stream, group, "$", "MKSTREAM");
            console.info({ service: group, stream, message: "consumer group created" });
        }
        catch (error) {
            if (!String(error).includes("BUSYGROUP")) {
                throw error;
            }
        }
    }
    async pruneDeadConsumers(streams, group, consumerName) {
        for (const stream of streams) {
            try {
                const result = await this.redis.call("XINFO", "CONSUMERS", stream, group);
                if (!Array.isArray(result))
                    continue;
                for (const entry of result) {
                    if (!Array.isArray(entry))
                        continue;
                    const name = fieldValue(entry, "name");
                    if (!name || name === consumerName)
                        continue;
                    const idle = parseInt(fieldValue(entry, "idle") ?? "0", 10);
                    if (idle > DEAD_CONSUMER_IDLE_MS) {
                        const pending = fieldValue(entry, "pending") ?? "0";
                        await this.redis.xgroup("DELCONSUMER", stream, group, name);
                        console.info({
                            service: group,
                            stream,
                            consumer: name,
                            idle,
                            pending: parseInt(pending, 10),
                            message: "pruned dead consumer",
                        });
                    }
                }
            }
            catch {
                // best-effort cleanup; stream or group may not exist yet
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
    const publisherRedis = createRedisClient(url, {}, "publisher");
    const subscriberRedis = createRedisClient(url, {}, "subscriber");
    await Promise.all([connectRedisWithRetry(publisherRedis, "publisher"), connectRedisWithRetry(subscriberRedis, "subscriber")]);
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
/**
 * Connect a lazy client, retrying indefinitely with capped backoff.
 *
 * `Redis#connect()` rejects if the very first TCP attempt fails (ioredis sets
 * status "end" and flushes the queue in that path), which turned a Redis that
 * happened to be down at boot into a hard startup failure. Retrying here means
 * startup waits for Redis instead of dying, and the client that comes out is
 * one that reconnects on its own afterwards.
 */
export async function connectRedisWithRetry(client, name = "redis") {
    for (let attempt = 1;; attempt += 1) {
        const status = client.status;
        if (status === "ready" || status === "connect" || status === "connecting") {
            return client;
        }
        try {
            await client.connect();
            return client;
        }
        catch (error) {
            const delay = redisRetryStrategy(attempt);
            console.warn({
                name,
                attempt,
                retryInMs: delay,
                error: sanitizedErrorForLog(error),
                message: "redis connect failed; retrying",
            });
            await sleep(delay);
        }
    }
}
async function handleBatchWithConsumerSpans(handler, envelopes, stream, consumerGroup) {
    if (envelopes.length === 0) {
        return [];
    }
    const spans = envelopes.map((envelope) => startConsumerSpan({
        stream,
        consumerGroup,
        eventId: envelope.eventId,
        eventType: envelope.eventType,
        correlationId: envelope.correlationId,
        parentContext: remoteTraceContext(envelope.traceparent, envelope.tracestate),
    }));
    try {
        const result = await handler(envelopes);
        const results = Array.isArray(result) ? result : envelopes.map(() => result);
        for (const [index, span] of spans.entries()) {
            const rawResult = results[index];
            const normalized = rawResult === undefined ? "ack" : normalizeHandlerResult(rawResult);
            if (normalized !== "ack") {
                markSpanError(span, String(failureReasonFromHandlerResult(rawResult)));
            }
            endSpan(span);
        }
        return result;
    }
    catch (error) {
        for (const span of spans) {
            endSpanWithError(span, error);
        }
        throw error;
    }
}
async function handleWithConsumerSpan(handler, envelope, stream, consumerGroup) {
    const span = startConsumerSpan({
        stream,
        consumerGroup,
        eventId: envelope.eventId,
        eventType: envelope.eventType,
        correlationId: envelope.correlationId,
        parentContext: remoteTraceContext(envelope.traceparent, envelope.tracestate),
    });
    try {
        const result = await handler(envelope);
        const normalized = result === undefined ? "ack" : normalizeHandlerResult(result);
        if (normalized !== "ack") {
            markSpanError(span, String(failureReasonFromHandlerResult(result)));
        }
        endSpan(span);
        return result;
    }
    catch (error) {
        endSpanWithError(span, error);
        throw error;
    }
}
function deserializeEventEnvelope(envelopeJson) {
    const raw = JSON.parse(envelopeJson);
    return Object.freeze({ ...raw, payload: Object.freeze(raw.payload ?? {}) });
}
function truncateFailureReason(reason) {
    const text = reason instanceof Error
        ? `${reason.name || "Error"}: ${reason.message || "Handler failed"}`
        : String(reason || "unknown");
    return text.length > 500 ? text.slice(0, 500) : text;
}
function eventIdForLog(envelopeJson) {
    try {
        const envelope = JSON.parse(envelopeJson);
        return typeof envelope.eventId === "string" ? envelope.eventId : "unknown";
    }
    catch {
        return "unknown";
    }
}
function isRecoverableRedisReadError(error) {
    const message = String(error);
    return message.includes("NOGROUP") || message.includes("Connection is closed") || message.includes("Connection is not established") || message.includes("ECONNREFUSED") || message.includes("ETIMEDOUT") || message.includes("ECONNRESET") || message.includes("EPIPE") || message.includes("READONLY") || message.includes("LOADING") || message.includes("MaxRetriesPerRequestError") || message.includes("max retries per request");
}
/**
 * A restarted (non-persistent) Redis has no streams and no consumer groups,
 * so XREADGROUP fails with NOGROUP until the group is re-created.
 */
export function isMissingGroupError(error) {
    const message = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
    return message.includes("NOGROUP");
}
/** Capped exponential backoff for a failing poll loop: 250ms -> 5s. */
function pollBackoffMs(consecutiveFailures) {
    return Math.min(250 * 2 ** Math.min(Math.max(consecutiveFailures, 1) - 1, 5), 5_000);
}
/** Capped exponential backoff before restarting a crashed loop: 500ms -> 30s. */
function loopRestartDelayMs(restarts) {
    return Math.min(500 * 2 ** Math.min(Math.max(restarts, 1) - 1, 6), REDIS_MAX_RECONNECT_DELAY_MS);
}
function sanitizedErrorForLog(error) {
    if (error instanceof Error) {
        return { name: error.name || "Error", message: error.message || "Handler failed", stack: error.stack };
    }
    return { name: typeof error, message: String(error || "Handler failed") };
}
function failureReasonForLog(reason) {
    if (reason instanceof Error) {
        return sanitizedErrorForLog(reason);
    }
    return { name: typeof reason, message: String(reason || "HandlerResult.retry") };
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
function failureReasonFromHandlerResult(result) {
    if (result === undefined || result === "ack" || result === "retry") {
        return "HandlerResult.dlq";
    }
    if (result === "dlq") {
        return "HandlerResult.dlq";
    }
    if (!result.ok) {
        return result.error ?? `HandlerResult.${result.kind ?? result.errorType ?? "transient"}`;
    }
    return "HandlerResult.dlq";
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
