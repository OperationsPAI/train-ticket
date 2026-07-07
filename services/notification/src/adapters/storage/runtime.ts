import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { Redis } from "ioredis";

import {
  MigrationRunner,
  OutboxAppender,
  OutboxRelay,
  ProcessedEventsGuard,
  createPostgresPool,
  streamForProducer,
  withTransaction,
  type EventEnvelope,
} from "@trainticket/ts-kit";

import { NonConformantNotificationTrigger, NotificationApplicationService } from "../../application/notification-service.js";
import { type NotificationTask } from "../../domain.js";
import { redisUrl } from "../messaging/stream-config.js";
import { PostgresNotificationTaskRepository, PostgresUserPreferenceRepository, listInAppNotifications } from "./notification-repository.js";

export type NotificationStorageRuntime = Readonly<{
  ready: () => boolean;
  failure: () => unknown;
  handleExternalTrigger: (envelope: EventEnvelope, stream?: string) => Promise<"ack" | "retry" | "dlq">;
  listInAppNotifications: (recipientRef: string, limit?: number) => Promise<readonly unknown[]>;
  stop: () => Promise<void>;
}>;

export async function startNotificationStorage(): Promise<NotificationStorageRuntime> {
  const pool = createPostgresPool();
  const migrations = new MigrationRunner(pool, migrationsDirectory());
  try {
    await migrations.apply();
  } catch (error) {
    console.error(sanitizedErrorForLog(error));
  }

  const redis = new Redis(redisUrl(), { lazyConnect: true });
  await redis.connect();
  const relay = new OutboxRelay(pool, redis, {
    pollIntervalMs: 250,
    onFailure: (error) => console.error(sanitizedErrorForLog(error)),
  });
  relay.start();

  return {
    ready: () => migrations.isReady,
    failure: () => migrations.failure,
    handleExternalTrigger: async (envelope, stream) => withTransaction(pool, async (client) => {
      const guard = new ProcessedEventsGuard(client);
      if (!await guard.tryStart(envelope.eventId, stream)) {
        return "ack";
      }

      const appender = new OutboxAppender(client);
      const publisher = new TransactionalOutboxPublisher(appender);
      const application = new NotificationApplicationService(
        publisher,
        new PostgresUserPreferenceRepository(client),
        undefined,
        new TransactionalNotificationTaskStore(new PostgresNotificationTaskRepository(client)),
      );

      try {
        await application.handleExternalTrigger(envelope);
        return "ack";
      } catch (error) {
        if (error instanceof NonConformantNotificationTrigger) {
          return "dlq";
        }
        throw error;
      }
    }),
    listInAppNotifications: async (recipientRef, limit) => withTransaction(pool, (client) => listInAppNotifications(client, recipientRef, limit)),
    stop: async () => {
      await relay.stop();
      await Promise.allSettled([redis.quit(), pool.end()]);
    },
  };
}

class TransactionalOutboxPublisher {
  constructor(private readonly appender: OutboxAppender) {}

  async publish(envelope: EventEnvelope): Promise<void> {
    await this.appender.append(envelope, streamForProducer(envelope.producer));
  }
}

class TransactionalNotificationTaskStore {
  constructor(private readonly repository: PostgresNotificationTaskRepository) {}

  async saveNew(task: NotificationTask): Promise<{ version: bigint }> {
    return this.repository.saveNew(task.toSnapshot());
  }

  async save(task: NotificationTask, expectedVersion: bigint): Promise<{ version: bigint }> {
    return this.repository.save(task.toSnapshot(), expectedVersion);
  }
}

export function migrationsDirectory(): string {
  return join(dirname(fileURLToPath(import.meta.url)), "../../../migrations");
}

function sanitizedErrorForLog(error: unknown): Readonly<{ name: string; message: string }> {
  if (error instanceof Error) {
    return { name: error.name || "Error", message: error.message || "Storage relay failed" };
  }
  return { name: typeof error, message: "Storage relay failed" };
}
