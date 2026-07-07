import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { Redis } from "ioredis";

import {
  MigrationRunner,
  OptimisticConcurrencyConflict,
  OutboxAppender,
  OutboxRelay,
  ProcessedEventsGuard,
  checkPostgresReadiness,
  createPostgresPool,
  streamForProducer,
  withTransaction,
  type EventEnvelope,
} from "@trainticket/ts-kit";

import { NonConformantNotificationTrigger, NotificationApplicationService } from "../../application/notification-service.js";
import { type NotificationTask } from "../../domain.js";
import { redisUrl } from "../messaging/stream-config.js";
import { PostgresNotificationTaskRepository, PostgresUserPreferenceRepository } from "./notification-repository.js";

export type NotificationStorageRuntime = Readonly<{
  ready: () => Promise<boolean>;
  failure: () => unknown;
  handleExternalTrigger: (envelope: EventEnvelope, stream?: string) => Promise<"ack" | "retry" | "dlq">;
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
    ready: () => migrations.isReady ? checkPostgresReadiness(pool) : Promise.resolve(false),
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
        if (error instanceof OptimisticConcurrencyConflict && await sameBusinessTaskExists(client, envelope)) {
          return "ack";
        }
        throw error;
      }
    }),
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

export async function isDuplicateBusinessNotification(client: Pick<import("pg").PoolClient, "query">, envelope: EventEnvelope): Promise<boolean> {
  return sameBusinessTaskExists(client, envelope);
}

async function sameBusinessTaskExists(client: Pick<import("pg").PoolClient, "query">, envelope: EventEnvelope): Promise<boolean> {
  const signature = notificationBusinessSignature(envelope);
  if (!signature) {
    return false;
  }
  const result = await client.query(
    `SELECT 1
     FROM notification_task_snapshots
     WHERE data->>'recipientRef' = $1
       AND data->>'templateCode' = $2
       AND data->>'triggerBusinessRef' = $3
     LIMIT 1`,
    [signature.recipientRef, signature.templateCode, signature.triggerBusinessRef],
  );
  return (result.rowCount ?? 0) > 0;
}

function notificationBusinessSignature(envelope: EventEnvelope): Readonly<{ recipientRef: string; templateCode: string; triggerBusinessRef: string }> | undefined {
  const templateCode = templateCodeFor(envelope.eventType);
  const recipientRef = recipientRefFor(envelope.payload);
  const triggerBusinessRef = triggerBusinessRefFor(envelope);
  return templateCode && recipientRef && triggerBusinessRef ? { recipientRef, templateCode, triggerBusinessRef } : undefined;
}

function templateCodeFor(eventType: string): string | undefined {
  switch (eventType) {
    case "JourneyOrderCreated":
      return "order_created";
    case "JourneyOrderPendingPayment":
      return "order_pending_payment";
    case "JourneyOrderPaymentRecorded":
      return "order_payment_recorded";
    case "JourneyOrderConfirmed":
      return "order_confirmed";
    case "JourneyOrderCancelled":
      return "order_cancelled";
    case "JourneyOrderPostSalesAdjusted":
    case "JourneyOrderAdjusted":
      return "order_adjusted";
    case "PaymentCaptured":
      return "payment_captured";
    case "PaymentFailed":
    case "PaymentIntentFailed":
      return "payment_failed";
    case "PaymentIntentExpired":
    case "PaymentExpired":
      return "payment_expired";
    case "RefundSettled":
      return "refund_settled";
    case "EntitlementIssued":
      return "ticket_issued";
    case "PostSalesEligibilityEvaluated":
      return "post_sales_eligibility";
    case "PostSalesDecisionQuoted":
      return "post_sales_decision";
    case "PostSalesExecutionStarted":
      return "post_sales_execution";
    case "PostSalesApplied":
      return "post_sales_applied";
    case "PostSalesFailed":
      return "post_sales_failed";
    case "ChangeApplied":
      return "change_applied";
    default:
      return undefined;
  }
}

function recipientRefFor(payload: Record<string, unknown>): string | undefined {
  return stringValue(payload.recipientRef) ?? stringValue(payload.travelerId) ?? stringValue(payload.accountId) ?? stringValue(payload.actorRef) ?? stringValue(payload.travelerRef) ?? recipientFromTravelerRefs(payload.travelerRefs);
}

function recipientFromTravelerRefs(value: unknown): string | undefined {
  if (!Array.isArray(value)) {
    return undefined;
  }
  for (const candidate of value) {
    if (typeof candidate === "string" && candidate.trim().length > 0) {
      return candidate;
    }
    if (candidate && typeof candidate === "object") {
      const ref = candidate as Record<string, unknown>;
      const recipientRef = stringValue(ref.recipientRef) ?? stringValue(ref.travelerId) ?? stringValue(ref.travelerRef) ?? stringValue(ref.id);
      if (recipientRef) {
        return recipientRef;
      }
    }
  }
  return undefined;
}

function triggerBusinessRefFor(envelope: EventEnvelope): string | undefined {
  const payload = envelope.payload;
  const direct = stringValue(payload.orderId)
    ?? stringValue(payload.journeyOrderId)
    ?? stringValue(payload.paymentIntentId)
    ?? stringValue(payload.refundId)
    ?? stringValue(payload.entitlementId)
    ?? stringValue(payload.segmentBookingId)
    ?? stringValue(payload.caseId)
    ?? stringValue(payload.postSalesCaseId)
    ?? stringValue(payload.businessRef);
  return direct ? `${envelope.eventType}:${direct}` : undefined;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
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
