import { Redis } from "ioredis";

import { activeTraceContext, endSpan, endSpanWithError, markSpanError, remoteTraceContext, startConsumerSpan } from "./observability.js";
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
  traceparent?: string;
  tracestate?: string;
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
export type EventHandlerOutput = EventHandlerResult | StringHandlerResult | undefined;
export type EventBatchHandlerOutput = EventHandlerOutput | readonly EventHandlerOutput[];
export type EventBatchHandler = (envelopes: readonly EventEnvelope[]) => Promise<EventBatchHandlerOutput> | EventBatchHandlerOutput;
export type EventHandler = ((envelope: EventEnvelope) => Promise<any> | any) & { handleBatch?: EventBatchHandler };

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
  const traceContext = activeTraceContext();
  return Object.freeze({
    eventId: input.eventId ? canonicalEventId(input.eventId) : newEventId(),
    eventType: input.eventType,
    schemaVersion: input.schemaVersion ?? 1,
    producer: input.producer,
    causationId: input.causationId ? canonicalCausationId(input.causationId) : newCommandId(),
    correlationId: input.correlationId ? canonicalCorrelationId(input.correlationId) : newCorrelationId(),
    occurredAt: occurredAt(input.occurredAt),
    payload: Object.freeze(omitUndefined(input.payload)) as TPayload,
    ...traceContext,
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
    const rawResult = await handleWithConsumerSpan(this.handler, envelope, "in-memory", "in-memory");
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

  reset(): void {
    this.received.length = 0;
    this.handler = undefined;
    this.processedEventIds.clear();
  }
}

const READ_BLOCK_MS = 1_000;
const READ_COUNT = 50;
const CLAIM_MIN_IDLE_MS = 60_000;
const MAX_DELIVERIES = 5;
const STREAM_MAXLEN = 100_000;

export type SubscriberLoopFailureHandler = (error: unknown) => void;

type StreamEntry = [id: string, fields: string[]];
type StreamMessages = [stream: string, entries: StreamEntry[]];
type BatchEntry = Readonly<{ entry: StreamEntry; envelope: EventEnvelope; attempts: number }>;

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
      try {
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
        await this.processMessages(messages, group, consumerName, handler);
      } catch (error) {
        // Non-persistent redis loses consumer groups on restart; recreate then back off so a dead connection never hot-spins.
        console.warn({
          service: group,
          stream: streams.join(","),
          eventId: "unknown",
          deliveries: 0,
          error: sanitizedErrorForLog(error),
          message: "poll read failed; recreating group if missing and backing off",
        });
        if (String(error).includes("NOGROUP")) {
          await Promise.all(streams.map((stream) => this.createGroup(stream, group)));
        }
        if (!isRecoverableRedisReadError(error)) {
          // Unknown errors are reported, never fatal: an unlisted driver
          // message must not silently kill the subscriber loop.
          this.onLoopFailure(error);
        }
        await sleep(1_000);
      }
    }
  }

  private async recover(streams: readonly string[], group: string, consumerName: string, handler: EventHandler, signal?: AbortSignal): Promise<void> {
    while (!this.stopped && !signal?.aborted) {
      await sleepUntil(() => this.stopped || signal?.aborted === true, CLAIM_MIN_IDLE_MS);
      if (this.stopped || signal?.aborted) {
        return;
      }
      for (const stream of streams) {
        try {
          await this.claimAndProcess(stream, group, consumerName, handler);
        } catch (error) {
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

  private async processMessages(messages: StreamMessages[] | null, group: string, consumerName: string, handler: EventHandler): Promise<void> {
    for (const [stream, entries] of messages ?? []) {
      await this.processEntries(stream, group, consumerName, entries, handler);
    }
  }

  private async processEntries(stream: string, group: string, consumerName: string, entries: readonly StreamEntry[], handler: EventHandler): Promise<void> {
    if (!handler.handleBatch || entries.length <= 1) {
      for (const entry of entries) {
        await this.processEntry(stream, group, consumerName, entry, handler);
      }
      return;
    }

    const batch: BatchEntry[] = [];
    for (const entry of entries) {
      const parsed = await this.parseBatchEntry(stream, group, consumerName, entry, 1);
      if (parsed) {
        batch.push(parsed);
      }
    }
    if (batch.length === 0) {
      return;
    }

    let batchResult: EventBatchHandlerOutput;
    try {
      batchResult = await handleBatchWithConsumerSpans(handler.handleBatch, batch.map(({ envelope }) => envelope), stream, group);
    } catch (error) {
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
      await this.finishEntry(
        stream,
        group,
        consumerName,
        item.entry,
        item.envelope,
        item.attempts,
        rawResult === undefined ? "ack" : normalizeHandlerResult(rawResult),
        failureReasonFromHandlerResult(rawResult),
      );
    }
  }

  private async parseBatchEntry(stream: string, group: string, consumerName: string, entry: StreamEntry, deliveryAttempts: number): Promise<BatchEntry | undefined> {
    const [entryId, fields] = entry;
    const attempts = Math.max(1, deliveryAttempts);
    const envelopeJson = fieldValue(fields, "envelope");
    if (!envelopeJson) {
      await this.deadLetterAndAck(stream, group, consumerName, entry, "MissingEnvelope", attempts);
      return undefined;
    }

    let envelope: EventEnvelope;
    try {
      envelope = deserializeEventEnvelope(envelopeJson);
    } catch (error) {
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

  private async processEntry(stream: string, group: string, consumerName: string, entry: StreamEntry, handler: EventHandler, deliveryAttempts = 1): Promise<void> {
    const [entryId, fields] = entry;
    const attempts = Math.max(1, deliveryAttempts);
    const envelopeJson = fieldValue(fields, "envelope");
    if (!envelopeJson) {
      await this.deadLetterAndAck(stream, group, consumerName, entry, "MissingEnvelope", attempts);
      return;
    }

    let envelope: EventEnvelope;
    try {
      envelope = deserializeEventEnvelope(envelopeJson);
    } catch (error) {
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

    let result: "ack" | "retry" | "dlq";
    let failureReason: unknown = "HandlerResult.dlq";
    try {
      const rawResult = await handleWithConsumerSpan(handler, envelope, stream, group);
      result = rawResult === undefined ? "ack" : normalizeHandlerResult(rawResult);
      failureReason = failureReasonFromHandlerResult(rawResult);
    } catch (error) {
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

  private async finishEntry(stream: string, group: string, consumerName: string, entry: StreamEntry, envelope: EventEnvelope, attempts: number, result: "ack" | "retry" | "dlq", failureReason: unknown): Promise<void> {
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

  private async claimAndProcess(stream: string, group: string, consumerName: string, handler: EventHandler): Promise<void> {
    const claimed = await this.redis.xautoclaim(stream, group, consumerName, CLAIM_MIN_IDLE_MS, "0", "COUNT", 100) as unknown[];
    const entries = (Array.isArray(claimed[1]) ? claimed[1] : []) as StreamEntry[];
    const deliveryCounts = await this.deliveryCounts(stream, group, entries.map(([entryId]) => entryId));
    for (const entry of entries) {
      if ((deliveryCounts.get(entry[0]) ?? 1) >= MAX_DELIVERIES) {
        await this.deadLetterAndAck(stream, group, consumerName, entry, "MaxDeliveries", deliveryCounts.get(entry[0]) ?? MAX_DELIVERIES);
      } else {
        await this.processEntry(stream, group, consumerName, entry, handler, deliveryCounts.get(entry[0]) ?? 1);
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

  private async deadLetterAndAck(stream: string, group: string, consumerName: string, entry: StreamEntry, reason: unknown, attempts: number): Promise<void> {
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
    await this.redis.xadd(
      dlqForStream(stream),
      "MAXLEN",
      "~",
      STREAM_MAXLEN,
      "*",
      "envelope",
      envelopeJson,
      "consumerGroup",
      group,
      "consumerName",
      consumerName,
      "failureReason",
      failureReason,
      "attempts",
      String(safeAttempts),
      "deadLetteredAt",
      deadLetteredAt,
    );
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

async function handleBatchWithConsumerSpans(handler: EventBatchHandler, envelopes: readonly EventEnvelope[], stream: string, consumerGroup: string): Promise<EventBatchHandlerOutput> {
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
  } catch (error) {
    for (const span of spans) {
      endSpanWithError(span, error);
    }
    throw error;
  }
}

async function handleWithConsumerSpan(handler: EventHandler, envelope: EventEnvelope, stream: string, consumerGroup: string): Promise<EventHandlerOutput> {
  const span = startConsumerSpan({
    stream,
    consumerGroup,
    eventId: envelope.eventId,
    eventType: envelope.eventType,
    correlationId: envelope.correlationId,
    parentContext: remoteTraceContext(envelope.traceparent, envelope.tracestate),
  });
  try {
    const result = await handler(envelope) as EventHandlerResult | StringHandlerResult | undefined;
    const normalized = result === undefined ? "ack" : normalizeHandlerResult(result);
    if (normalized !== "ack") {
      markSpanError(span, String(failureReasonFromHandlerResult(result)));
    }
    endSpan(span);
    return result;
  } catch (error) {
    endSpanWithError(span, error);
    throw error;
  }
}

function deserializeEventEnvelope(envelopeJson: string): EventEnvelope {
  const raw = JSON.parse(envelopeJson) as EventEnvelope;
  return Object.freeze({ ...raw, payload: Object.freeze(raw.payload ?? {}) });
}

function truncateFailureReason(reason: unknown): string {
  const text = reason instanceof Error
    ? `${reason.name || "Error"}: ${reason.message || "Handler failed"}`
    : String(reason || "unknown");
  return text.length > 500 ? text.slice(0, 500) : text;
}

function eventIdForLog(envelopeJson: string): string {
  try {
    const envelope = JSON.parse(envelopeJson) as { eventId?: unknown };
    return typeof envelope.eventId === "string" ? envelope.eventId : "unknown";
  } catch {
    return "unknown";
  }
}

function isRecoverableRedisReadError(error: unknown): boolean {
  const message = String(error);
  return message.includes("NOGROUP") || message.includes("Connection is closed") || message.includes("Connection is not established") || message.includes("ECONNREFUSED") || message.includes("ETIMEDOUT") || message.includes("READONLY") || message.includes("LOADING");
}

function sanitizedErrorForLog(error: unknown): Readonly<{ name: string; message: string; stack?: string }> {
  if (error instanceof Error) {
    return { name: error.name || "Error", message: error.message || "Handler failed", stack: error.stack };
  }
  return { name: typeof error, message: String(error || "Handler failed") };
}

function failureReasonForLog(reason: unknown): Readonly<{ name: string; message: string; stack?: string }> {
  if (reason instanceof Error) {
    return sanitizedErrorForLog(reason);
  }
  return { name: typeof reason, message: String(reason || "HandlerResult.retry") };
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

function failureReasonFromHandlerResult(result: EventHandlerResult | StringHandlerResult | undefined): unknown {
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
