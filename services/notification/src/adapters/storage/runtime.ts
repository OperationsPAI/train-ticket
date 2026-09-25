import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import {
  OptimisticConcurrencyConflict,
  DurableEventReplayWorker,
  OutboxAppender,
  OutboxRelay,
  PostgresDeadLetterEvidenceStore,
  ProcessedEventsGuard,
  connectRedisWithRetry,
  createPostgresPool,
  createRedisClient,
  eventReplayRetentionMs,
  migrationsAwareReadiness,
  redisClientLiveness,
  startMigrations,
  streamForProducer,
  withTransaction,
  type EventEnvelope,
  type DeadLetterEvidenceSink,
  type EventReplaySource,
  type MigrationSupervisor,
} from "@trainticket/ts-kit";

import { DirectSuccessGateway, type NotificationChannelGateway, NonConformantNotificationTrigger, NotificationApplicationService, notificationRecipients } from "../../application/notification-service.js";
import { RateLimitExceeded, type NotificationTask } from "../../domain.js";
import { redisUrl } from "../messaging/stream-config.js";
import { PostgresNotificationAggregator, PostgresNotificationTaskRepository, PostgresRateLimitRepository, PostgresRecipientContactRepository, PostgresUserPreferenceRepository } from "./notification-repository.js";
import { PostgresDeliveryScheduler, startDeliveryWorker } from "./delivery-queue.js";
import type { NotificationTrailResponse } from "../../app.js";

export type NotificationStorageRuntime = Readonly<{
  ready: () => Promise<boolean>;
  failure: () => unknown;
  /** Migration retry state, surfaced on `/readyz` so a stuck boot is diagnosable. */
  schema: () => ReturnType<MigrationSupervisor["detail"]>;
  getNotificationTrail: (notificationId: string) => Promise<NotificationTrailResponse | undefined>;
  handleExternalTrigger: (envelope: EventEnvelope, stream?: string) => Promise<"ack" | "retry" | "dlq">;
  deadLetterEvidenceSink: DeadLetterEvidenceSink;
  stop: () => Promise<void>;
}>;

export async function startNotificationStorage(channelGateway: NotificationChannelGateway = new DirectSuccessGateway()): Promise<NotificationStorageRuntime> {
  const pool = createPostgresPool();
  // A migration failure used to be caught, logged once and abandoned, which
  // wedged `isReady` false and `/readyz` at 503 for the life of the process
  // (2026-09-07 Postgres OOMKill). `startMigrations` awaits the first attempt --
  // so a successful boot is unchanged and the schema is in place before we
  // serve -- and, if it failed, keeps retrying in the background on the shared
  // capped-backoff schedule until the database comes back. See
  // `superviseMigrations` for the readiness/liveness reasoning.
  const migrations = await startMigrations(pool, migrationsDirectory(), { service: "notification" });

  // createRedisClient attaches an "error" listener and an infinite capped
  // backoff retry strategy, and registers a liveness component; `new Redis`
  // got none of that (2026-09-06 outage).
  const redis = createRedisClient(redisUrl(), {}, "notification-outbox");
  await connectRedisWithRetry(redis, "notification-outbox");
  const relay = new OutboxRelay(pool, redis, {
    pollIntervalMs: 250,
    processedEventRetentionMs: eventReplayRetentionMs(),
    name: "notification-outbox-relay",
    onFailure: (error) => console.error(sanitizedErrorForLog(error)),
  });
  relay.start();
  const deliveryWorker = startDeliveryWorker(pool, channelGateway);
  const deadLetterEvidenceSink = new PostgresDeadLetterEvidenceStore(pool);

  const handleExternalTrigger = async (original: EventEnvelope, stream?: string): Promise<"ack" | "retry" | "dlq"> => {
    if (!templateCodeFor(original)) return "ack";
    return withTransaction(pool, async (client) => {
      const guard = new ProcessedEventsGuard(client);
      if (!await guard.tryStart(original.eventId, stream)) {
        return "ack";
      }
      const envelope = await resolveRecipient(client, original);
      const result = await deliverOrDefer(client, envelope, stream, channelGateway);
      if (result === "dlq") {
        // A fatal trigger is evidence, not a successfully consumed business fact.
        // Keep it eligible for an explicit replay after the contract/data issue is
        // corrected; otherwise the processed-event guard would make recovery look
        // successful without re-running validation.
        await client.query("DELETE FROM processed_events WHERE event_id=$1", [original.eventId]);
      }
      return result;
    });
  };
  const replay = new DurableEventReplayWorker(
    pool,
    notificationReplaySources(),
    handleExternalTrigger,
    { consumerGroup: "notification", pollIntervalMs: replayPollIntervalMs(), deadLetterEvidenceSink },
  );
  let stopping = false;
  if (migrations.isReady()) replay.start();
  else void migrations.applied().then(() => { if (!stopping) replay.start(); });

  let retrying: Promise<void> | undefined;
  const retryTimer = setInterval(() => {
    if (retrying) return;
    retrying = retryDeferredNotifications(pool, channelGateway)
      .catch((error) => console.error(sanitizedErrorForLog(error)))
      .finally(() => { retrying = undefined; });
  }, 1000);
  retryTimer.unref();

  return {
    ready: migrationsAwareReadiness(migrations, pool),
    failure: () => migrations.failure(),
    schema: () => migrations.detail(),
    getNotificationTrail: notificationId => getNotificationTrail(pool, notificationId),
    deadLetterEvidenceSink,
    handleExternalTrigger,
    stop: async () => {
      stopping = true;
      clearInterval(retryTimer);
      await retrying;
      await replay.stop();
      await deliveryWorker.stop();
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

export function notificationReplaySources(): readonly EventReplaySource[] {
  return [
    { name: "post-sales", producer: "post-sales", endpoint: process.env.POST_SALES_EVENT_REPLAY_URL ?? "http://post-sales:8080/api/v1/internal/event-replay" },
    { name: "payment", producer: "payment", endpoint: process.env.PAYMENT_EVENT_REPLAY_URL ?? "http://payment:8080/api/v1/internal/event-replay" },
    { name: "wallet-promotion", producer: "wallet-promotion", endpoint: process.env.WALLET_PROMOTION_EVENT_REPLAY_URL ?? "http://wallet-promotion:8080/api/v1/internal/event-replay" },
  ];
}

function replayPollIntervalMs(): number {
  const configured = Number(process.env.EVENT_REPLAY_POLL_INTERVAL_MS ?? "5000");
  return Number.isFinite(configured) && configured >= 100 ? configured : 5_000;
}

export async function getNotificationTrail(pool: import("pg").Pool, notificationId: string): Promise<NotificationTrailResponse | undefined> {
  return withTransaction(pool, async client => {
    await client.query("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
    const record = await new PostgresNotificationTaskRepository(client).get(notificationId);
    if (!record) return undefined;
    const queue = await client.query("SELECT state,message_id,available_at FROM notification_delivery_queue WHERE task_id=$1", [notificationId]);
    const attempts = queue.rowCount ? await client.query("SELECT claim_token,attempt_number,channel,state,claimed_at,started_at,completed_at,message_id,result FROM notification_delivery_attempts WHERE task_id=$1 ORDER BY attempt_number", [notificationId]) : undefined;
    const evidence = queue.rowCount ? await client.query("SELECT seq,claim_token,kind,recorded_at,evidence FROM notification_delivery_evidence WHERE task_id=$1 ORDER BY seq", [notificationId]) : undefined;
    return {
      notificationId, status: record.data.status, resultUnknown: record.data.status === "Unknown" || queue.rows[0]?.state === "Unknown",
      attempts: record.data.receipts.map(receipt => ({
        channelUsed: receipt.channel, attemptedAt: new Date(receipt.recordedAt).toISOString(),
        status: receipt.outcome === "Delivered" ? "DELIVERED" : receipt.outcome === "Unknown" ? "UNKNOWN" : receipt.outcome === "Bounced" ? "BOUNCED" : "FAILED",
        attemptId: receipt.attemptId, messageId: receipt.messageId, retryAt: receipt.retryAt,
        ...(receipt.providerCode ? { reason: receipt.providerCode } : {}),
      })),
      ...(queue.rows[0] ? { delivery: {
        state: queue.rows[0].state, messageId: queue.rows[0].message_id, availableAt: queue.rows[0].available_at,
        attempts: attempts!.rows, evidence: evidence!.rows,
      } } : {}),
    };
  });
}

export async function deliverOrDefer(client: import("pg").PoolClient, envelope: EventEnvelope, stream: string | undefined, channelGateway: NotificationChannelGateway): Promise<"ack" | "dlq"> {
  let retryAfter: Date | undefined;
  let invalid = false;
  for (const recipient of [...notificationRecipients(envelope)].sort()) {
    await client.query("SAVEPOINT notification_delivery");
    try {
      await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [recipient]);
      if (!await sameBusinessTaskExists(client, envelope, [recipient])) {
        const application = new NotificationApplicationService(
          new TransactionalOutboxPublisher(new OutboxAppender(client)),
          new PostgresUserPreferenceRepository(client), channelGateway,
          new TransactionalNotificationTaskStore(new PostgresNotificationTaskRepository(client)),
          new PostgresRecipientContactRepository(client), new PostgresRateLimitRepository(client),
          new PostgresNotificationAggregator(client),
          new PostgresDeliveryScheduler(client),
        );
        await application.handleExternalTrigger(envelope, recipient);
      }
      await client.query("RELEASE SAVEPOINT notification_delivery");
    } catch (error) {
      await client.query("ROLLBACK TO SAVEPOINT notification_delivery");
      await client.query("RELEASE SAVEPOINT notification_delivery");
      if (error instanceof RateLimitExceeded) {
        if (!retryAfter || error.retryAfter < retryAfter) retryAfter = error.retryAfter;
        continue;
      }
      if (error instanceof NonConformantNotificationTrigger) { invalid = true; continue; }
      if (error instanceof OptimisticConcurrencyConflict && await sameBusinessTaskExists(client, envelope, [recipient])) continue;
      throw error;
    }
  }
  if (retryAfter) {
    await client.query(
      "INSERT INTO notification_deferred(event_id,stream,envelope,retry_at) VALUES($1,$2,$3,$4) ON CONFLICT(event_id) DO UPDATE SET retry_at=EXCLUDED.retry_at,updated_at=now()",
      [envelope.eventId, stream, JSON.stringify(envelope), retryAfter],
    );
  } else {
    await client.query("DELETE FROM notification_deferred WHERE event_id=$1", [envelope.eventId]);
  }
  return invalid ? "dlq" : "ack";
}

export async function retryDeferredNotifications(pool: import("pg").Pool, channelGateway: NotificationChannelGateway): Promise<void> {
  for (let index = 0; index < 8; index++) {
    const found = await withTransaction(pool, async (client) => {
      const result = await client.query("SELECT envelope,stream FROM notification_deferred WHERE retry_at <= now() ORDER BY retry_at LIMIT 1 FOR UPDATE SKIP LOCKED");
      if (!result.rows[0]) return false;
      const envelope = result.rows[0].envelope as EventEnvelope;
      const status = await deliverOrDefer(client, envelope, result.rows[0].stream, channelGateway);
      if (status === "dlq") throw new NonConformantNotificationTrigger("Deferred notification is no longer valid");
      return true;
    });
    if (!found) break;
  }
}

export async function resolveRecipient(pool: Pick<import("pg").PoolClient, "query">, envelope: EventEnvelope): Promise<EventEnvelope> {
  const payload = envelope.payload;
  let recipient = stringValue(payload.accountId) ?? stringValue(payload.payerRef) ?? stringValue(payload.actorRef);
  const businessRef = stringValue(payload.businessRef);
  const orderId = stringValue(payload.orderId) ?? stringValue(payload.journeyOrderId) ?? (businessRef?.startsWith("ord-") ? businessRef : undefined);
  const caseId = stringValue(payload.caseId);
  const paymentId = stringValue(payload.paymentIntentId);
  const directRecipient = stringValue(payload.recipientRef) ?? stringValue(payload.travelerId) ?? stringValue(payload.travelerRef) ?? stringValue(payload.riderAccountId);
  if (!recipient && !directRecipient && templateCodeFor(envelope)) {
    const refs = [["order", orderId], ["case", caseId], ["payment", paymentId]].filter((entry) => entry[1]);
    for (const [kind, id] of refs) {
      const result = await pool.query("SELECT recipient_ref FROM notification_recipient_refs WHERE kind=$1 AND id=$2", [kind, id]);
      if (result.rows[0]) { recipient = result.rows[0].recipient_ref; break; }
    }
    if (!recipient && (orderId || caseId || paymentId)) {
      const service = orderId ? "journey-order" : caseId ? "post-sales" : "payment";
      const path = orderId ? `/api/v1/journey-orders/${encodeURIComponent(orderId)}` : caseId ? `/api/v1/post-sales-cases/${encodeURIComponent(caseId)}` : `/api/v1/payment-intents/${encodeURIComponent(paymentId!)}`;
      const response = await fetch(`http://${service}:8080${path}`, { signal: AbortSignal.timeout(5000) });
      if (!response.ok) throw new Error(`Recipient lookup ${service} returned ${response.status}`);
      const details = await response.json() as Record<string, unknown>;
      recipient = stringValue(details.accountId) ?? stringValue(details.actorRef) ?? stringValue(details.payerRef);
      if (!recipient) throw new Error(`Recipient missing in ${service} response`);
    }
  }
  if (!recipient) return envelope;
  for (const [kind, id] of [["order", orderId], ["case", caseId], ["payment", paymentId]]) {
    if (id) await pool.query("INSERT INTO notification_recipient_refs (kind,id,recipient_ref) VALUES ($1,$2,$3) ON CONFLICT (kind,id) DO NOTHING", [kind, id, recipient]);
  }
  return { ...envelope, payload: { ...payload, accountId: recipient } };
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

async function sameBusinessTaskExists(client: Pick<import("pg").PoolClient, "query">, envelope: EventEnvelope, recipients = notificationRecipients(envelope)): Promise<boolean> {
  const signatures = notificationBusinessSignatures(envelope).filter(signature => recipients.includes(signature.recipientRef));
  if (signatures.length === 0) {
    return false;
  }

  for (const signature of signatures) {
    const result = await client.query(
      `SELECT 1
       FROM notification_task_snapshots
       WHERE data->>'recipientRef' = $1
         AND data->>'templateCode' = $2
         AND data->>'triggerBusinessRef' = $3
       LIMIT 1`,
      [signature.recipientRef, signature.templateCode, signature.triggerBusinessRef],
    );
    if ((result.rowCount ?? 0) === 0) return false;
  }
  return true;
}

function notificationBusinessSignatures(envelope: EventEnvelope): readonly Readonly<{ recipientRef: string; templateCode: string; triggerBusinessRef: string }>[] {
  const templateCode = templateCodeFor(envelope);
  const recipientRefs = notificationRecipients(envelope);
  const triggerBusinessRef = triggerBusinessRefFor(envelope);
  return templateCode && triggerBusinessRef
    ? recipientRefs.map((recipientRef) => ({ recipientRef, templateCode, triggerBusinessRef }))
    : [];
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
      return "ticket_issued";
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
    case "AncillaryOfferQuoted":
      return "ANCILLARY_OFFER_QUOTED";
    case "AncillaryOfferExpired":
      return "ANCILLARY_OFFER_EXPIRED";
    case "AncillaryOrderItemSelected":
      return "ANCILLARY_ORDER_ITEM_SELECTED";
    case "AncillaryOrderItemPendingConfirmation":
      return "ANCILLARY_ORDER_ITEM_PENDING_CONFIRMATION";
    case "AncillaryOrderItemConfirmed":
      return "ANCILLARY_ORDER_ITEM_CONFIRMED";
    case "AncillaryOrderItemFulfillmentReady":
      return "ANCILLARY_ORDER_ITEM_FULFILLMENT_READY";
    case "AncillaryOrderItemFulfilled":
      return "ANCILLARY_ORDER_ITEM_FULFILLED";
    case "AncillaryOrderItemFailed":
      return "ANCILLARY_ORDER_ITEM_FAILED";
    case "AncillaryOrderItemCancelled":
      return "ANCILLARY_ORDER_ITEM_CANCELLED";
    case "AncillaryOrderItemRefundPending":
      return "ANCILLARY_ORDER_ITEM_REFUND_PENDING";
    case "AncillaryOrderItemRefunded":
      return "ANCILLARY_ORDER_ITEM_REFUNDED";
    case "AncillaryFulfillmentFactRecorded":
      return "ANCILLARY_FULFILLMENT_FACT_RECORDED";
    case "DispatchRequested":
      return "DISPATCH_REQUESTED";
    case "DriverAssigned":
      return "DRIVER_ASSIGNED";
    case "DriverEtaUpdated":
      return "DRIVER_ETA_UPDATED";
    case "DriverArrived":
      return "DRIVER_ARRIVED";
    case "RideStarted":
      return "RIDE_STARTED";
    case "RideEnded":
      return "RIDE_ENDED";
    case "DriverCancelled":
      return "DRIVER_CANCELLED";
    case "DispatchUserCancelled":
      return "DISPATCH_USER_CANCELLED";
    case "DispatchNoShowRecorded":
      return "DISPATCH_NO_SHOW_RECORDED";
    case "DispatchFailed":
      return "DISPATCH_FAILED";
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

function triggerBusinessRefFor(envelope: EventEnvelope): string | undefined {
  const payload = envelope.payload;
  const connection = recordFromUnknown(payload.connection);
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
    ?? stringValue(payload.ancillaryOrderItemId)
    ?? stringValue(payload.ancillaryOfferId)
    ?? stringValue(payload.rideRequestId)
    ?? stringValue(connection?.connectionId)
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

function recordFromUnknown(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
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
