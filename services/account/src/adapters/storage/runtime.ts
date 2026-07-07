import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { Redis } from "ioredis";
import {
  MigrationRunner,
  OutboxAppender,
  OutboxRelay,
  PostgresIdempotencyStore,
  checkPostgresReadiness,
  createPostgresPool,
  streamForProducer,
  withTransaction,
  type EventEnvelope,
} from "@trainticket/ts-kit";
import { type Pool } from "pg";

import { AccountApplicationService } from "../../application.js";

import { PostgresAccountRepository } from "./account-repository.js";

export type AccountStorageRuntime = Readonly<{
  ready: () => Promise<boolean>;
  idempotencyStore: PostgresIdempotencyStore;
  runCommand: <T>(operation: (application: AccountApplicationService) => Promise<T>) => Promise<T>;
  stop: () => Promise<void>;
}>;

export async function startAccountStorage(): Promise<AccountStorageRuntime> {
  const pool = createPostgresPool();
  const migrations = new MigrationRunner(pool, migrationsDirectory());
  try {
    await migrations.apply();
  } catch (error) {
    console.error(sanitizedErrorForLog(error));
  }

  const redis = new Redis(process.env.REDIS_URL ?? "redis://localhost:6379", { lazyConnect: true });
  await redis.connect();
  const relay = new OutboxRelay(pool, redis, {
    pollIntervalMs: 250,
    onFailure: (error) => console.error(sanitizedErrorForLog(error)),
  });
  relay.start();

  return {
    ready: () => migrations.isReady ? checkPostgresReadiness(pool) : Promise.resolve(false),
    idempotencyStore: new PostgresIdempotencyStore(pool),
    runCommand: (operation) => withAccountTransaction(pool, operation),
    stop: async () => {
      await relay.stop();
      await Promise.allSettled([redis.quit(), pool.end()]);
    },
  };
}

async function withAccountTransaction<T>(pool: Pool, operation: (application: AccountApplicationService) => Promise<T>): Promise<T> {
  return withTransaction(pool, async (client) => {
    const publisher = new TransactionalOutboxPublisher(new OutboxAppender(client));
    return operation(new AccountApplicationService(new PostgresAccountRepository(client), publisher));
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
