import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import {
  OutboxAppender,
  OutboxRelay,
  PostgresIdempotencyStore,
  ProcessedEventsGuard,
  connectRedisWithRetry,
  createPostgresPool,
  createRedisClient,
  migrationsAwareReadiness,
  redisClientLiveness,
  startMigrations,
  streamForProducer,
  withTransaction,
  type EventEnvelope,
  type SchemaDetail,
} from "@trainticket/ts-kit";
import { type Pool } from "pg";

import { CustomerServiceApplication } from "../../application/customer-service.js";
import { type EventPublisher } from "../../application/messaging.js";
import { DEFAULT_REDIS_URL } from "../messaging/stream-config.js";
import { PostgresCustomerServiceRepository } from "./customer-service-repository.js";

export type CustomerServiceStorageRuntime = Readonly<{
  ready: () => Promise<boolean>;
  /** Migration retry state, surfaced on `/readyz` so a stuck boot is diagnosable. */
  schema: () => SchemaDetail;
  idempotencyStore: PostgresIdempotencyStore;
  runCommand: <T>(operation: (application: CustomerServiceApplication) => Promise<T>) => Promise<T>;
  runScheduledEvaluation: () => Promise<number>;
  handleIntegrationEvent: (envelope: EventEnvelope, stream?: string) => Promise<"ack" | "retry" | "dlq">;
  stop: () => Promise<void>;
}>;

export async function startCustomerServiceStorage(): Promise<CustomerServiceStorageRuntime> {
  const pool = createPostgresPool();
  // A migration failure used to be caught, logged once and abandoned, which
  // wedged `isReady` false and `/readyz` at 503 for the life of the process
  // (2026-09-07 Postgres OOMKill). `startMigrations` awaits the first attempt --
  // so a successful boot is unchanged and the schema is in place before we
  // serve -- and, if it failed, keeps retrying in the background on the shared
  // capped-backoff schedule until the database comes back. See
  // `superviseMigrations` for the readiness/liveness reasoning.
  const migrations = await startMigrations(pool, migrationsDirectory(), { service: "customer-service" });

  // createRedisClient attaches an "error" listener and an infinite capped
  // backoff retry strategy, and registers a liveness component. Without them
  // ioredis logged "[ioredis] Unhandled error event: ... ECONNREFUSED" once
  // and the relay never published again (2026-09-06 outage).
  const redis = createRedisClient(process.env.REDIS_URL ?? DEFAULT_REDIS_URL, {}, "customer-service-outbox");
  await connectRedisWithRetry(redis, "customer-service-outbox");
  const relay = new OutboxRelay(pool, redis as never, {
    pollIntervalMs: 250,
    name: "customer-service-outbox-relay",
    onFailure: (error) => console.error(sanitizedErrorForLog(error)),
  });
  relay.start();
  const slaScheduler = setInterval(() => {
    withCustomerServiceTransaction(pool, async (application) => {
      await application.evaluateOpenTickets();
    }).catch((error) => console.error(sanitizedErrorForLog(error)));
  }, 5_000);
  slaScheduler.unref?.();

  return {
    ready: migrationsAwareReadiness(migrations, pool),
    schema: () => migrations.detail(),
    idempotencyStore: new PostgresIdempotencyStore(pool),
    runCommand: (operation) => withCustomerServiceTransaction(pool, operation),
    runScheduledEvaluation: async () => {
      const evaluated = await withCustomerServiceTransaction(pool, (application) => application.evaluateOpenTickets());
      return evaluated.length;
    },
    handleIntegrationEvent: (envelope, stream) => withTransaction(pool, async (client): Promise<"ack" | "retry" | "dlq"> => {
      const guard = new ProcessedEventsGuard(client);
      if (!await guard.tryStart(envelope.eventId, stream)) {
        return "ack";
      }
      const publisher = new TransactionalOutboxPublisher(new OutboxAppender(client));
      const application = new CustomerServiceApplication(publisher, new PostgresCustomerServiceRepository(client));
      await application.handleIntegrationEvent(envelope as import("../../application/messaging.js").EventEnvelope);
      return "ack";
    }),
    stop: async () => {
      clearInterval(slaScheduler);
      // Stop the migration retry first: otherwise a shutdown during the retry
      // window leaves a backoff timer running and can issue queries against a
      // pool that is being torn down.
      await migrations.stop();
      await relay.stop();
      redisClientLiveness(redis)?.dispose();
      await Promise.allSettled([redis.quit(), pool.end()]);
    },
  };
}

async function withCustomerServiceTransaction<T>(pool: Pool, operation: (application: CustomerServiceApplication) => Promise<T>): Promise<T> {
  return withTransaction(pool, async (client) => {
    const publisher = new TransactionalOutboxPublisher(new OutboxAppender(client));
    return operation(new CustomerServiceApplication(publisher, new PostgresCustomerServiceRepository(client)));
  });
}

class TransactionalOutboxPublisher implements EventPublisher {
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
