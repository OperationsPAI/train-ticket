import { Redis } from "ioredis";

import { canonicalCausationId, canonicalCorrelationId, canonicalEventId, newCommandId, newCorrelationId, newEventId } from "./ids.js";

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

export class PublishFailed extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "PublishFailed";
  }
}

export class SubscribeFailed extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "SubscribeFailed";
  }
}

export class HandlerError extends Error {
  constructor(
    public readonly kind: "transient" | "fatal",
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = "HandlerError";
  }
}

export type HandlerErrorKind = "transient" | "fatal";
export type EventHandlerResult =
  | Readonly<{ ok: true }>
  | Readonly<{ ok: false; kind?: HandlerErrorKind; errorType?: HandlerErrorKind; error?: Error }>;
export type HandlerResult = EventHandlerResult;
export type StringHandlerResult = "ack" | "retry" | "dlq";
export type EventHandler = (envelope: EventEnvelope) => Promise<any> | any;

export interface EventPublisher {
  publish(envelope: EventEnvelope): Promise<void>;
}

export interface EventSubscriber {
  subscribe(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: EventHandler,
    signal?: AbortSignal,
  ): Promise<void>;
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

export function createEventEnvelope<TPayload extends Record<string, unknown>>(input: EnvelopeInput<TPayload>): EventEnvelope<TPayload> {
  return Object.freeze({
    eventId: input.eventId ? canonicalEventId(input.eventId) : newEventId(),
    eventType: input.eventType,
    schemaVersion: input.schemaVersion ?? 1,
    producer: input.producer,
    causationId: input.causationId ? canonicalCausationId(input.causationId) : newCommandId(),
    correlationId: input.correlationId ? canonicalCorrelationId(input.correlationId) : newCorrelationId(),
    occurredAt: occurredAt(input.occurredAt),
    payload: Object.freeze(omitUndefined(input.payload)) as TPayload,
  });
}

export async function publishAfterCommit<T>(transaction: () => Promise<T>, publisher: EventPublisher, envelopes: readonly EventEnvelope[] | ((result: T) => readonly EventEnvelope[] | Promise<readonly EventEnvelope[]>)): Promise<T> {
  const result = await transaction();
  const toPublish = typeof envelopes === "function" ? await envelopes(result) : envelopes;
  for (const envelope of toPublish) {
    await publisher.publish(envelope);
  }
  return result;
}

export function successfulHandling(): EventHandlerResult {
  return { ok: true };
}

export function transientHandling(error?: Error): EventHandlerResult {
  return { ok: false, kind: "transient", error };
}

export function fatalHandling(error?: Error): EventHandlerResult {
  return { ok: false, kind: "fatal", error };
}

export class InMemoryEventPublisher implements EventPublisher {
  public readonly envelopes: EventEnvelope[] = [];
  public readonly published = this.envelopes;
  public failNext = false;

  async publish(envelope: EventEnvelope): Promise<void> {
    if (this.failNext) {
      this.failNext = false;
      throw new PublishFailed("Simulated publish failure");
    }
    this.envelopes.push(structuredClone(envelope));
  }

  findByProducer(producer: string): EventEnvelope[] {
    return this.envelopes.filter((envelope) => envelope.producer === producer);
  }

  findByEventType(eventType: string): EventEnvelope[] {
    return this.envelopes.filter((envelope) => envelope.eventType === eventType);
  }

  reset(): void {
    this.envelopes.length = 0;
    this.failNext = false;
  }
}

export class DeduplicatingEventHandler {
  private readonly consumedEventIds = new Set<string>();

  constructor(private readonly delegate: EventHandler) {}

  async handle(envelope: EventEnvelope): Promise<EventHandlerResult | StringHandlerResult> {
    if (this.consumedEventIds.has(envelope.eventId)) {
      return successfulHandling();
    }
    const result = await this.delegate(envelope);
    if (result === undefined || handlerSucceeded(result)) {
      this.consumedEventIds.add(envelope.eventId);
    }
    return result ?? successfulHandling();
  }

  hasConsumed(eventId: string): boolean {
    return this.consumedEventIds.has(eventId);
  }
}

export class ConsumedEventDeduplicator {
  private readonly consumedEventIds = new Set<string>();

  async handle(envelope: EventEnvelope, handler: (envelope: EventEnvelope) => Promise<void>): Promise<boolean> {
    if (this.consumedEventIds.has(envelope.eventId)) {
      return false;
    }
    await handler(envelope);
    this.consumedEventIds.add(envelope.eventId);
    return true;
  }
}

export class InMemoryEventSubscriber implements EventSubscriber {
  public readonly received: EventEnvelope[] = [];
  private handler?: EventHandler;
  private readonly processedEventIds = new Set<string>();

  async subscribe(
    _streams: readonly string[],
    _group: string,
    _consumerName: string,
    handler: EventHandler,
    _signal?: AbortSignal,
  ): Promise<void> {
    this.handler = handler;
  }

  async simulateEvent(envelope: EventEnvelope): Promise<"ack" | "retry" | "dlq"> {
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

  async emit(envelope: EventEnvelope): Promise<boolean> {
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

  reset(): void {
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

export type SubscriberLoopFailureHandler = (error: unknown) => void;

type StreamEntry = [id: string, fields: string[]];
type StreamMessages = [stream: string, entries: StreamEntry[]];

export class RedisEventPublisher implements EventPublisher {
  constructor(private readonly redis: Redis) {}

  async publish(envelope: EventEnvelope): Promise<void> {
    const stream = streamForProducer(envelope.producer);
    const serialized = JSON.stringify(envelope);
    let lastError: unknown;
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      try {
        await this.redis.xadd(stream, "MAXLEN", "~", STREAM_MAXLEN, "*", "envelope", serialized);
        return;
      } catch (error) {
        lastError = error;
        if (attempt < 3) {
          await sleep(25 * 2 ** (attempt - 1));
        }
      }
    }
    throw new PublishFailed(`Failed to publish ${envelope.eventType}`, { cause: lastError });
  }

  async close(): Promise<void> {
    this.redis.disconnect();
  }
}

export class RedisStreamEventPublisher extends RedisEventPublisher {
  constructor(redis: Redis = new Redis(redisUrl(), { lazyConnect: true })) {
    super(redis);
  }
}

export class RedisEventSubscriber implements EventSubscriber {
  private stopped = false;
  private readonly consumedEventIds = new Set<string>();
  private readonly loops = new Set<Promise<void>>();
  private startedPromise: Promise<void>;
  private resolveStarted!: () => void;
  private rejectStarted!: (error: unknown) => void;

  constructor(
    private readonly redis: Redis = new Redis(redisUrl(), { lazyConnect: true }),
    private readonly onLoopFailure: SubscriberLoopFailureHandler = defaultLoopFailureHandler,
    private readonly options: Readonly<{ thrownHandlerErrors?: "retry" | "dlq" }> = {},
  ) {
    this.startedPromise = new Promise((resolve, reject) => {
      this.resolveStarted = resolve;
      this.rejectStarted = reject;
    });
  }

  started(): Promise<void> {
    return this.startedPromise;
  }

  async subscribe(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: EventHandler,
    signal?: AbortSignal,
  ): Promise<void> {
    try {
      await this.ensureConnected();
      await Promise.all(streams.map((stream) => this.createGroup(stream, group)));
      this.resolveStarted();
      this.startLoop(this.poll(streams, group, consumerName, handler, signal));
      this.startLoop(this.recover(streams, group, consumerName, handler, signal));
    } catch (error) {
      this.stopped = true;
      this.rejectStarted(error);
      throw new SubscribeFailed("Failed to subscribe to event streams", { cause: error });
    }
  }

  async stop(): Promise<void> {
    this.stopped = true;
    this.redis.disconnect();
    await Promise.allSettled([...this.loops]);
  }

  async close(): Promise<void> {
    await this.stop();
  }

  private startLoop(loop: Promise<void>): void {
    this.loops.add(loop);
    loop.catch((error) => {
      if (!this.stopped) {
        this.onLoopFailure(new SubscribeFailed("Redis subscriber background loop failed", { cause: error }));
      }
    }).finally(() => this.loops.delete(loop));
  }

  private async poll(streams: readonly string[], group: string, consumerName: string, handler: EventHandler, signal?: AbortSignal): Promise<void> {
    while (!this.stopped && !signal?.aborted) {
      const redisWithRead = this.redis as unknown as {
        call?: (...args: unknown[]) => Promise<unknown>;
        xreadgroup?: (...args: unknown[]) => Promise<unknown>;
      };
      const read = redisWithRead.call?.bind(this.redis) ?? redisWithRead.xreadgroup?.bind(this.redis);
      if (!read) {
        throw new TypeError("Redis client does not support XREADGROUP");
      }
      const messages = await read(
        "XREADGROUP",
        "GROUP",
        group,
        consumerName,
        "BLOCK",
        READ_BLOCK_MS,
        "COUNT",
        READ_COUNT,
        "STREAMS",
        ...streams,
        ...streams.map(() => ">"),
      ) as StreamMessages[] | null;
      await this.processMessages(messages, group, handler);
    }
  }

  private async recover(streams: readonly string[], group: string, consumerName: string, handler: EventHandler, signal?: AbortSignal): Promise<void> {
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

  private async processMessages(messages: StreamMessages[] | null, group: string, handler: EventHandler): Promise<void> {
    for (const [stream, entries] of messages ?? []) {
      for (const entry of entries) {
        await this.processEntry(stream, group, entry, handler);
      }
    }
  }

  private async processEntry(stream: string, group: string, entry: StreamEntry, handler: EventHandler): Promise<void> {
    const [entryId, fields] = entry;
    const envelopeJson = fieldValue(fields, "envelope");
    if (!envelopeJson) {
      await this.deadLetterAndAck(stream, group, entry);
      return;
    }

    let envelope: EventEnvelope;
    try {
      envelope = JSON.parse(envelopeJson) as EventEnvelope;
    } catch {
      await this.deadLetterAndAck(stream, group, entry);
      return;
    }

    if (this.consumedEventIds.has(envelope.eventId)) {
      await this.redis.xack(stream, group, entryId);
      return;
    }

    let result: "ack" | "retry" | "dlq";
    try {
      const rawResult = await handler(envelope);
      result = rawResult === undefined ? "ack" : normalizeHandlerResult(rawResult);
    } catch (error) {
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

  private async claimAndProcess(stream: string, group: string, consumerName: string, handler: EventHandler): Promise<void> {
    const claimed = await this.redis.xautoclaim(stream, group, consumerName, CLAIM_MIN_IDLE_MS, "0", "COUNT", 100) as unknown[];
    const entries = (Array.isArray(claimed[1]) ? claimed[1] : []) as StreamEntry[];
    const deliveryCounts = await this.deliveryCounts(stream, group, entries.map(([entryId]) => entryId));
    for (const entry of entries) {
      if ((deliveryCounts.get(entry[0]) ?? 1) >= MAX_DELIVERIES) {
        await this.deadLetterAndAck(stream, group, entry);
      } else {
        await this.processEntry(stream, group, entry, handler);
      }
    }
  }

  private async deliveryCounts(stream: string, group: string, entryIds: readonly string[]): Promise<Map<string, number>> {
    const counts = new Map<string, number>();
    await Promise.all(entryIds.map(async (entryId) => {
      const pending = await this.redis.xpending(stream, group, entryId, entryId, 1) as unknown[];
      const row = pending[0] as unknown[] | undefined;
      const deliveries = row?.[3];
      if (typeof deliveries === "number") {
        counts.set(entryId, deliveries);
      }
    }));
    return counts;
  }

  private async deadLetterAndAck(stream: string, group: string, entry: StreamEntry): Promise<void> {
    const envelopeJson = fieldValue(entry[1], "envelope") ?? JSON.stringify({});
    await this.redis.xadd(dlqForStream(stream), "MAXLEN", "~", STREAM_MAXLEN, "*", "envelope", envelopeJson);
    await this.redis.xack(stream, group, entry[0]);
  }

  private async createGroup(stream: string, group: string): Promise<void> {
    try {
      await this.redis.xgroup("CREATE", stream, group, "$", "MKSTREAM");
    } catch (error) {
      if (!String(error).includes("BUSYGROUP")) {
        throw error;
      }
    }
  }

  private async ensureConnected(): Promise<void> {
    const status = (this.redis as unknown as { status?: string }).status;
    if (status === "wait") {
      await this.redis.connect();
    }
  }
}

export class RedisStreamEventSubscriber extends RedisEventSubscriber {}

export type RedisMessagingAdapters = Readonly<{
  publisher: RedisEventPublisher;
  subscriber: RedisEventSubscriber;
  close: () => Promise<void>;
}>;

export async function createRedisMessagingAdapters(url: string = redisUrl()): Promise<RedisMessagingAdapters> {
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

export function streamForProducer(producer: string): string {
  return `events:${producer}`;
}

export const streamKey = streamForProducer;

export function dlqForStream(stream: string): string {
  return `${stream}:dlq`;
}

export function dlqStreamKey(context: string): string {
  return `events:${context}:dlq`;
}

export function redisUrl(): string {
  return process.env.REDIS_URL ?? "redis://localhost:6379";
}

function sanitizedErrorForLog(error: unknown): Readonly<{ name: string; message: string }> {
  if (error instanceof Error) {
    return { name: error.name || "Error", message: error.message || "Handler failed" };
  }
  return { name: typeof error, message: "Handler failed" };
}

function normalizeHandlerResult(result: EventHandlerResult | StringHandlerResult): "ack" | "retry" | "dlq" {
  if (result === "ack" || result === "retry" || result === "dlq") {
    return result;
  }
  if (result.ok) {
    return "ack";
  }
  const kind = result.kind ?? result.errorType;
  return kind === "fatal" ? "dlq" : "retry";
}

function handlerSucceeded(result: EventHandlerResult | StringHandlerResult): boolean {
  return normalizeHandlerResult(result) === "ack";
}

function occurredAt(value: Date | string | undefined): string {
  if (value instanceof Date) {
    return value.toISOString();
  }
  if (typeof value === "string") {
    return new Date(value).toISOString();
  }
  return new Date().toISOString();
}

function omitUndefined<T extends Record<string, unknown>>(values: T): Record<string, unknown> {
  const payload: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined) {
      payload[key] = value;
    }
  }
  return payload;
}

function fieldValue(fields: string[], name: string): string | undefined {
  for (let index = 0; index < fields.length; index += 2) {
    if (fields[index] === name) {
      return fields[index + 1];
    }
  }
  return undefined;
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, milliseconds);
    timer.unref?.();
  });
}

async function sleepUntil(stopped: () => boolean, milliseconds: number): Promise<void> {
  const deadline = Date.now() + milliseconds;
  while (!stopped() && Date.now() < deadline) {
    await sleep(Math.min(100, deadline - Date.now()));
  }
}

function defaultLoopFailureHandler(error: unknown): void {
  console.error(sanitizedErrorForLog(error));
}
