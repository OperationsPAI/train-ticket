import { Redis } from "ioredis";
export type EventEnvelope<TPayload extends Record<string, unknown> = Record<string, unknown>> = Readonly<{
    eventId: string;
    eventType: string;
    schemaVersion: number;
    producer: string;
    causationId?: string;
    correlationId: string;
    occurredAt: string;
    payload: TPayload;
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
export type EventHandler = (envelope: EventEnvelope) => Promise<any> | any;
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
export type SubscriberLoopFailureHandler = (error: unknown) => void;
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
    constructor(redis?: Redis, onLoopFailure?: SubscriberLoopFailureHandler, options?: Readonly<{
        thrownHandlerErrors?: "retry" | "dlq";
    }>);
    started(): Promise<void>;
    subscribe(streams: readonly string[], group: string, consumerName: string, handler: EventHandler, signal?: AbortSignal): Promise<void>;
    stop(): Promise<void>;
    close(): Promise<void>;
    private startLoop;
    private poll;
    private recover;
    private processMessages;
    private processEntry;
    private claimAndProcess;
    private deliveryCounts;
    private deadLetterAndAck;
    private createGroup;
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
