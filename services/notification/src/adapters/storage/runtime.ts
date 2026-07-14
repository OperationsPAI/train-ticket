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

import { DirectSuccessGateway, type NotificationChannelGateway, NonConformantNotificationTrigger, NotificationApplicationService } from "../../application/notification-service.js";
import { NotificationAggregator, RateLimitExceeded, type NotificationTask } from "../../domain.js";
import { redisUrl } from "../messaging/stream-config.js";
import { PostgresNotificationTaskRepository, PostgresRateLimitRepository, PostgresRecipientContactRepository, PostgresUserPreferenceRepository } from "./notification-repository.js";

export type NotificationStorageRuntime = Readonly<{
  ready: () => Promise<boolean>;
  failure: () => unknown;
  getNotificationTrail: (notificationId: string) => Promise<Readonly<{ notificationId: string; status: string; attempts: readonly Readonly<{ channelUsed: string; attemptedAt: string; status: string; reason?: string }>[] }> | undefined>;
  handleExternalTrigger: (envelope: EventEnvelope, stream?: string) => Promise<"ack" | "retry" | "dlq">;
  stop: () => Promise<void>;
}>;

export async function startNotificationStorage(channelGateway: NotificationChannelGateway = new DirectSuccessGateway()): Promise<NotificationStorageRuntime> {
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

  const aggregator = new NotificationAggregator();

  return {
    ready: () => migrations.isReady ? checkPostgresReadiness(pool) : Promise.resolve(false),
    failure: () => migrations.failure,
    getNotificationTrail: async (notificationId) => withTransaction(pool, async (client) => {
      const record = await new PostgresNotificationTaskRepository(client).get(notificationId);
      if (!record) {
        return undefined;
      }
      const snapshot = record.data;
      return {
        notificationId,
        status: snapshot.status,
        attempts: snapshot.receipts.map((receipt) => ({
          channelUsed: receipt.channel,
          attemptedAt: new Date(receipt.recordedAt).toISOString(),
          status: receipt.outcome === "Delivered" ? "DELIVERED" : receipt.outcome === "Bounced" ? "BOUNCED" : "FAILED",
          ...(receipt.providerCode ? { reason: receipt.providerCode } : {}),
        })),
      };
    }),
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
        channelGateway,
        new TransactionalNotificationTaskStore(new PostgresNotificationTaskRepository(client)),
        new PostgresRecipientContactRepository(client),
        new PostgresRateLimitRepository(client),
        aggregator,
      );

      try {
        await application.handleExternalTrigger(envelope);
        return "ack";
      } catch (error) {
        if (error instanceof NonConformantNotificationTrigger) {
          return "dlq";
        }
        if (error instanceof RateLimitExceeded) {
          throw error;
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
  const templateCode = templateCodeFor(envelope);
  const recipientRef = recipientRefFor(envelope.payload);
  const triggerBusinessRef = triggerBusinessRefFor(envelope);
  return templateCode && recipientRef && triggerBusinessRef ? { recipientRef, templateCode, triggerBusinessRef } : undefined;
}

function templateCodeFor(envelope: EventEnvelope): string | undefined {
  switch (envelope.eventType) {
    case "JourneyOrderCreated":
      return "order_created";
    case "JourneyOrderPendingPayment":
      return "order_pending_payment";
    case "JourneyOrderPaymentRecorded":
      return "order_payment_recorded";
    case "JourneyOrderConfirmed":
      return "ORDER_CONFIRMED";
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
      return "PAYMENT_REMINDER";
    case "RefundSettled":
      return "REFUND_COMPLETED";
    case "EntitlementIssued":
      return "TICKET_ISSUED";
    case "WaitlistFulfilled":
      return "WAITLIST_PROMOTED";
    case "ServiceAlertPublished":
      return "DISRUPTION_ALERT";
    case "RecoveryCaseOpened":
      return "RECOVERY_CASE_OPENED";
    case "RecoveryOptionsGenerated":
      return hasReaccommodation(envelope.payload) ? "RECOVERY_REACCOMMODATION" : "RECOVERY_OPTIONS_AVAILABLE";
    case "RecoveryOptionSelected":
      return stringValue(envelope.payload.optionType) === "REACCOMMODATION" ? "RECOVERY_REACCOMMODATION" : "RECOVERY_OPTION_SELECTED";
    case "RecoveryExecutionStarted":
      return stringValue(envelope.payload.optionType) === "REACCOMMODATION" ? "RECOVERY_REACCOMMODATION" : "RECOVERY_EXECUTION_STARTED";
    case "RecoveryCompleted":
      return recoveryCompletedTemplate(envelope.payload);
    case "RecoveryFailed":
      return "RECOVERY_FAILED";
    case "TransferAtRisk":
      return "TRANSFER_AT_RISK";
    case "ConnectionMissed":
      return "CONNECTION_MISSED";
    case "ConnectionRecovered":
      return stringValue(envelope.payload.replacementConnectionId) ? "CONNECTION_REACCOMMODATED" : "CONNECTION_RECOVERED";
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
    case "BenefitIssued":
      return "wallet_benefit_issued";
    case "BenefitExpired":
      return "wallet_benefit_expired";
    case "BenefitRevoked":
      return "wallet_benefit_revoked";
    default:
      return undefined;
  }
}

function recipientRefFor(payload: Record<string, unknown>): string | undefined {
  return stringValue(payload.recipientRef)
    ?? stringValue(payload.travelerId)
    ?? stringValue(payload.accountId)
    ?? stringValue(payload.actorRef)
    ?? stringValue(payload.travelerRef)
    ?? firstString(payload.affectedOrderIds)
    ?? stringValue(payload.journeyOrderId)
    ?? stringValue(payload.serviceAlertId)
    ?? recipientFromTravelerRefs(payload.travelerRefs);
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
    ?? stringValue(payload.benefitId)
    ?? stringValue(payload.segmentBookingId)
    ?? stringValue(payload.caseId)
    ?? stringValue(payload.serviceAlertId)
    ?? stringValue(payload.incidentId)
    ?? stringValue(payload.waitlistRequestId)
    ?? stringValue(payload.journeyOrderRef)
    ?? stringValue(payload.postSalesCaseId)
    ?? stringValue(payload.businessRef);
  return direct ? `${envelope.eventType}:${direct}` : undefined;
}

function hasReaccommodation(payload: Record<string, unknown>): boolean {
  return stringValue(payload.optionType) === "REACCOMMODATION"
    || (Array.isArray(payload.options) && payload.options.some((option) => stringValue((option as Record<string, unknown>)?.optionType) === "REACCOMMODATION"));
}

function recoveryCompletedTemplate(payload: Record<string, unknown>): string {
  switch (stringValue(payload.optionType)) {
    case "REFUND":
      return "RECOVERY_REFUND_EXECUTED";
    case "COMPENSATION":
      return "RECOVERY_COMPENSATION_ISSUED";
    case "REACCOMMODATION":
      return "RECOVERY_REACCOMMODATION";
    default:
      return "RECOVERY_COMPLETED";
  }
}

function firstString(value: unknown): string | undefined {
  if (!Array.isArray(value)) {
    return undefined;
  }
  return value.find((candidate): candidate is string => typeof candidate === "string" && candidate.trim().length > 0);
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
