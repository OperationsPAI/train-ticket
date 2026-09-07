import { Redis, type RedisOptions } from "ioredis";
import { type LivenessComponent } from "./liveness.js";
export type EventEnvelope<TPayload extends Record<string, unknown> = Record<string, unknown>> = Readonly<{
    eventId: string;
    eventType: string;
    schemaVersion: number;
    producer: string;
    causationId?: string;
    correlationId: string;
    occurredAt: string;
    payload: TPayload;
    traceparent?: string;
    tracestate?: string;
}>;
export declare class PublishFailed extends Error {
    constructor(message: string, options?: ErrorOptions);
}
export declare class SubscribeFailed extends Error {
    constructor(message: string, options?: ErrorOptions);
}
export declare class HandlerError extends Error {
    readonly kind: "transient" | "fatal";
    constructor(kind: "transient" | "fatal", message: string, options?: ErrorOptions);
}
export type HandlerErrorKind = "transient" | "fatal";
export type EventHandlerResult = Readonly<{
    ok: true;
}> | Readonly<{
    ok: false;
    kind?: HandlerErrorKind;
    errorType?: HandlerErrorKind;
    error?: Error;
}>;
export type HandlerResult = EventHandlerResult;
export type StringHandlerResult = "ack" | "retry" | "dlq";
export type EventHandlerOutput = EventHandlerResult | StringHandlerResult | undefined;
export type EventBatchHandlerOutput = EventHandlerOutput | readonly EventHandlerOutput[];
export type EventBatchHandler = (envelopes: readonly EventEnvelope[]) => Promise<EventBatchHandlerOutput> | EventBatchHandlerOutput;
export type EventHandler = ((envelope: EventEnvelope) => Promise<any> | any) & {
    handleBatch?: EventBatchHandler;
};
export interface EventPublisher {
    publish(envelope: EventEnvelope): Promise<void>;
}
export interface EventSubscriber {
    subscribe(streams: readonly string[], group: string, consumerName: string, handler: EventHandler, signal?: AbortSignal): Promise<void>;
    stop?(): Promise<void>;
    close?(): Promise<void>;
}
export type EnvelopeInput<TPayload extends Record<string, unknown>> = Readonly<{
    eventType: string;
    producer: string;
    payload: TPayload;
    schemaVersion?: number;
    eventId?: string;
    causationId?: string;
    correlationId?: string;
    occurredAt?: Date | string;
}>;
export declare function createEventEnvelope<TPayload extends Record<string, unknown>>(input: EnvelopeInput<TPayload>): EventEnvelope<TPayload>;
export declare function publishAfterCommit<T>(transaction: () => Promise<T>, publisher: EventPublisher, envelopes: readonly EventEnvelope[] | ((result: T) => readonly EventEnvelope[] | Promise<readonly EventEnvelope[]>)): Promise<T>;
export declare function successfulHandling(): EventHandlerResult;
export declare function transientHandling(error?: Error): EventHandlerResult;
export declare function fatalHandling(error?: Error): EventHandlerResult;
export declare class InMemoryEventPublisher implements EventPublisher {
    readonly envelopes: EventEnvelope[];
    readonly published: Readonly<{
        eventId: string;
        eventType: string;
        schemaVersion: number;
        producer: string;
        causationId?: string;
        correlationId: string;
        occurredAt: string;
        payload: Record<string, unknown>;
        traceparent?: string;
        tracestate?: string;
    }>[];
    failNext: boolean;
    publish(envelope: EventEnvelope): Promise<void>;
    findByProducer(producer: string): EventEnvelope[];
    findByEventType(eventType: string): EventEnvelope[];
    reset(): void;
}
export declare class DeduplicatingEventHandler {
    private readonly delegate;
    private readonly consumedEventIds;
    constructor(delegate: EventHandler);
    handle(envelope: EventEnvelope): Promise<EventHandlerResult | StringHandlerResult>;
    hasConsumed(eventId: string): boolean;
}
export declare class ConsumedEventDeduplicator {
    private readonly consumedEventIds;
    handle(envelope: EventEnvelope, handler: (envelope: EventEnvelope) => Promise<void>): Promise<boolean>;
}
export declare class InMemoryEventSubscriber implements EventSubscriber {
    readonly received: EventEnvelope[];
    private handler?;
    private readonly processedEventIds;
    subscribe(_streams: readonly string[], _group: string, _consumerName: string, handler: EventHandler, _signal?: AbortSignal): Promise<void>;
    simulateEvent(envelope: EventEnvelope): Promise<"ack" | "retry" | "dlq">;
    emit(envelope: EventEnvelope): Promise<boolean>;
    reset(): void;
}
export declare const STREAM_MAXLEN_ENV = "EVENT_STREAM_MAXLEN";
export declare const DEFAULT_STREAM_MAXLEN = 10000;
/**
 * Resolves the per-stream entry cap. A blank, non-numeric or non-positive value falls
 * back to the default rather than throwing or trimming to zero: a misconfigured cap that
 * silently discarded every published event would be the worst outcome here.
 */
export declare function streamMaxLen(configured: string | undefined): number;
export declare function configuredStreamMaxLen(): number;
export type SubscriberLoopFailureHandler = (error: unknown) => void;
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
export declare function redisRetryStrategy(attempt: number): number;
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
export declare function redisClientOptions(overrides?: RedisOptions): RedisOptions;
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
export declare function createRedisClient(url?: string, overrides?: RedisOptions, name?: string): Redis;
/** The liveness component tracking this client, if it was built by createRedisClient. */
export declare function redisClientLiveness(client: Redis): LivenessComponent | undefined;
export declare function attachRedisLifecycleLogging(client: Redis, name: string): LivenessComponent;
export declare class RedisEventPublisher implements EventPublisher {
    private readonly redis;
    constructor(redis: Redis);
    publish(envelope: EventEnvelope): Promise<void>;
    close(): Promise<void>;
}
export declare class RedisStreamEventPublisher extends RedisEventPublisher {
    constructor(redis?: Redis);
}
export declare class RedisEventSubscriber implements EventSubscriber {
    private readonly redis;
    private readonly onLoopFailure;
    private readonly options;
    private stopped;
    private readonly consumedEventIds;
    private readonly loops;
    private startedPromise;
    private resolveStarted;
    private rejectStarted;
    private consumerLiveness?;
    /**
     * Set whenever the Redis connection drops. A restarted Redis has no
     * consumer groups (this system treats Redis as transport only and replays
     * from the Postgres outbox), so the groups must be re-created before the
     * next read instead of erroring on NOGROUP forever.
     */
    private groupsNeedRecreate;
    constructor(redis?: Redis, onLoopFailure?: SubscriberLoopFailureHandler, options?: Readonly<{
        thrownHandlerErrors?: "retry" | "dlq";
    }>);
    started(): Promise<void>;
    subscribe(streams: readonly string[], group: string, consumerName: string, handler: EventHandler, signal?: AbortSignal): Promise<void>;
    stop(): Promise<void>;
    close(): Promise<void>;
    /**
     * Track connection lifecycle so that (a) a reconnect triggers consumer
     * group re-creation and (b) a permanently disconnected client is visible
     * to the liveness probe.
     */
    private watchConnection;
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
    private superviseLoop;
    private poll;
    private recover;
    private processMessages;
    private processEntries;
    private parseBatchEntry;
    private processEntry;
    private finishEntry;
    private claimAndProcess;
    private deliveryCounts;
    private deadLetterAndAck;
    private createGroup;
    private pruneDeadConsumers;
    private ensureConnected;
}
export declare class RedisStreamEventSubscriber extends RedisEventSubscriber {
}
export type RedisMessagingAdapters = Readonly<{
    publisher: RedisEventPublisher;
    subscriber: RedisEventSubscriber;
    close: () => Promise<void>;
}>;
export declare function createRedisMessagingAdapters(url?: string): Promise<RedisMessagingAdapters>;
export declare function streamForProducer(producer: string): string;
export declare const streamKey: typeof streamForProducer;
export declare function dlqForStream(stream: string): string;
export declare function dlqStreamKey(context: string): string;
export declare function redisUrl(): string;
/**
 * Connect a lazy client, retrying indefinitely with capped backoff.
 *
 * `Redis#connect()` rejects if the very first TCP attempt fails (ioredis sets
 * status "end" and flushes the queue in that path), which turned a Redis that
 * happened to be down at boot into a hard startup failure. Retrying here means
 * startup waits for Redis instead of dying, and the client that comes out is
 * one that reconnects on its own afterwards.
 */
export declare function connectRedisWithRetry(client: Redis, name?: string): Promise<Redis>;
/**
 * A restarted (non-persistent) Redis has no streams and no consumer groups,
 * so XREADGROUP fails with NOGROUP until the group is re-created.
 */
export declare function isMissingGroupError(error: unknown): boolean;
