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
import { applyUpstreamEvent, buildQuoteOfferCommand, type UpstreamStateRepository } from "../../application/upstream-state.js";
import { PostgresOfferRepository } from "./offer-repository.js";
import { PostgresUpstreamStateRepository } from "./upstream-state-repository.js";

export type OfferStorageRuntime = Readonly<{
  ready: () => Promise<boolean>;
  idempotencyStore: PostgresIdempotencyStore;
  runCommand: <T>(upstreamRepository: UpstreamStateRepository, operation: (application: OfferApplicationService) => Promise<T>) => Promise<T>;
  handleUpstreamEvent: (envelope: EventEnvelope, stream?: string) => Promise<"ack" | "retry" | "dlq">;
  stop: () => Promise<void>;
}>;

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
    pollIntervalMs: 250,
    onFailure: (error) => console.error(sanitizedErrorForLog(error)),
  });
  relay.start();

  return {
    ready: () => migrations.isReady ? checkPostgresReadiness(pool) : Promise.resolve(false),
    idempotencyStore: new PostgresIdempotencyStore(pool),
    runCommand: (upstreamRepository, operation) => withOfferTransaction(pool, upstreamRepository, operation),
    handleUpstreamEvent: (envelope, stream) => withTransaction(pool, async (client): Promise<"ack" | "retry" | "dlq"> => {
      const guard = new ProcessedEventsGuard(client);
      if (!await guard.tryStart(envelope.eventId, stream)) {
        return "ack";
      }
      await applyUpstreamEvent(new PostgresUpstreamStateRepository(client), envelope);
      return "ack";
    }),
    stop: async () => {
      await relay.stop();
      await Promise.allSettled([redis.quit(), pool.end()]);
    },
  };
}

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
