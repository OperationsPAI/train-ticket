import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { Redis } from "ioredis";
import {
  MigrationRunner,
  OutboxAppender,
  OutboxRelay,
  PostgresIdempotencyStore,
  ProcessedEventsGuard,
  checkPostgresReadiness,
  createPostgresPool,
  streamForProducer,
  withTransaction,
  type EventEnvelope,
} from "@trainticket/ts-kit";
import { type Pool } from "pg";

import { OfferApplicationService } from "../../application/offers.js";
import { applyUpstreamEvents, buildQuoteOfferCommand, type UpstreamStateRepository } from "../../application/upstream-state.js";
import { PostgresOfferRepository } from "./offer-repository.js";
import { PostgresUpstreamStateRepository } from "./upstream-state-repository.js";

export type OfferStorageRuntime = Readonly<{
  ready: () => Promise<boolean>;
  idempotencyStore: PostgresIdempotencyStore;
  runCommand: <T>(upstreamRepository: UpstreamStateRepository, operation: (application: OfferApplicationService) => Promise<T>) => Promise<T>;
  handleUpstreamEvent: UpstreamEventHandler;
  stop: () => Promise<void>;
}>;

type UpstreamEventHandler = ((envelope: EventEnvelope, stream?: string) => Promise<"ack" | "retry" | "dlq">) & {
  handleBatch?: (envelopes: readonly EventEnvelope[]) => Promise<"ack" | "retry" | "dlq">;
};

export async function startOfferStorage(redisUrl = process.env.REDIS_URL ?? "redis://localhost:6379"): Promise<OfferStorageRuntime> {
  const pool = createPostgresPool();
  const migrations = new MigrationRunner(pool, migrationsDirectory());
  try {
    await migrations.apply();
  } catch (error) {
    console.error(sanitizedErrorForLog(error));
  }

  const redis = new Redis(redisUrl, { lazyConnect: true });
  await redis.connect();
  const relay = new OutboxRelay(pool, redis, {
    pollIntervalMs: parseInt(process.env.OUTBOX_POLL_INTERVAL_MS || "50", 10),
    onFailure: (error) => console.error(sanitizedErrorForLog(error)),
  });
  relay.start();

  return {
    ready: () => migrations.isReady ? checkPostgresReadiness(pool) : Promise.resolve(false),
    idempotencyStore: new PostgresIdempotencyStore(pool),
    runCommand: (upstreamRepository, operation) => withOfferTransaction(pool, upstreamRepository, operation),
    handleUpstreamEvent: createUpstreamEventHandler(pool),
    stop: async () => {
      await relay.stop();
      await Promise.allSettled([redis.quit(), pool.end()]);
    },
  };
}

function createUpstreamEventHandler(pool: Pool): UpstreamEventHandler {
  const handler = ((envelope: EventEnvelope, stream?: string) => (
    handleUpstreamEventBatch(pool, [{ envelope, stream }])
  )) as UpstreamEventHandler;
  handler.handleBatch = (envelopes) => handleUpstreamEventBatch(
    pool,
    envelopes.map((envelope) => ({ envelope, stream: streamForProducer(envelope.producer) })),
  );
  return handler;
}

async function handleUpstreamEventBatch(pool: Pool, events: readonly UpstreamEventBatchItem[]): Promise<"ack" | "retry" | "dlq"> {
  if (events.length === 0) {
    return "ack";
  }

  return withTransaction(pool, async (client): Promise<"ack" | "retry" | "dlq"> => {
    const guard = new ProcessedEventsGuard(client);
    const startedEventIds = new Set(await guard.tryStartMany(events.map(({ envelope, stream }) => ({ eventId: envelope.eventId, stream }))));
    const appliedEventIds = new Set<string>();
    const envelopesToApply: EventEnvelope[] = [];
    for (const { envelope } of events) {
      if (startedEventIds.has(envelope.eventId) && !appliedEventIds.has(envelope.eventId)) {
        envelopesToApply.push(envelope);
        appliedEventIds.add(envelope.eventId);
      }
    }
    await applyUpstreamEvents(new PostgresUpstreamStateRepository(client), envelopesToApply);
    return "ack";
  });
}

type UpstreamEventBatchItem = Readonly<{ envelope: EventEnvelope; stream?: string }>;

async function withOfferTransaction<T>(pool: Pool, _upstreamRepository: UpstreamStateRepository, operation: (application: OfferApplicationService) => Promise<T>): Promise<T> {
  return withTransaction(pool, async (client) => {
    const publisher = new TransactionalOutboxPublisher(new OutboxAppender(client));
    const persistedUpstreamRepository = new PostgresUpstreamStateRepository(client);
    const application = new OfferApplicationService(
      new PostgresOfferRepository(client),
      publisher,
      (request) => buildQuoteOfferCommand(persistedUpstreamRepository, request),
    );
    return operation(application);
  });
}

class TransactionalOutboxPublisher {
  constructor(private readonly appender: OutboxAppender) {}

  async publish(envelope: EventEnvelope): Promise<void> {
    await this.appender.append(envelope, streamForProducer(envelope.producer));
  }
}

export function migrationsDirectory(): string {
  return join(dirname(fileURLToPath(import.meta.url)), "../../../migrations");
}

function sanitizedErrorForLog(error: unknown): Readonly<{ name: string; message: string }> {
  if (error instanceof Error) {
    return { name: error.name || "Error", message: error.message || "Storage failed" };
  }
  return { name: typeof error, message: "Storage failed" };
}
