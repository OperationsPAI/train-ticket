import { randomUUID } from "node:crypto";
import type { Pool, PoolClient } from "pg";
import { OutboxAppender, withTransaction } from "@trainticket/ts-kit";
import { NotificationTask, newReceiptId, type NotificationTaskSnapshot } from "../../domain.js";
import type { DeliveryChannel, DeliveryResult, NotificationChannelGateway, NotificationDeliveryScheduler } from "../../application/notification-service.js";
import { toEventEnvelope } from "../../application/messaging.js";
import { smtpMessageId } from "../channel-gateways.js";
import { deliveryErrorEvidence } from "../delivery-errors.js";
import { PostgresNotificationTaskRepository, PostgresRateLimitRepository } from "./notification-repository.js";

const CLAIM_LEASE_SECONDS = 30;
const MAX_CHANNEL_ATTEMPTS = 3;
type QueueRow = {
  task_id: string; channels: DeliveryChannel[]; channel_index: number; state: string;
  claim_token: string; attempt_number: number; message_id: string;
};
export type DeliveryClaim = Readonly<{ taskId: string; token: string }>;

async function evidence(client: PoolClient, row: QueueRow, kind: string, details: unknown): Promise<void> {
  await client.query("INSERT INTO notification_delivery_evidence(task_id,claim_token,kind,evidence) VALUES($1,$2,$3,$4)",
    [row.task_id, row.claim_token, kind, JSON.stringify(details)]);
}

export class PostgresDeliveryScheduler implements NotificationDeliveryScheduler {
  constructor(private readonly client: PoolClient) {}

  async enqueue(task: NotificationTaskSnapshot, channels: readonly DeliveryChannel[]): Promise<void> {
    const decision = await new PostgresRateLimitRepository(this.client).reserve(task.notificationTaskId, task.recipientRef, channels[0].channel, new Date());
    await this.client.query(
      "INSERT INTO notification_delivery_queue(task_id,channels,state,available_at,message_id) VALUES($1,$2,'Ready',$3,$4)",
      [task.notificationTaskId, JSON.stringify(channels), decision.allowed ? new Date() : decision.retryAfter, smtpMessageId(task.notificationTaskId)],
    );
    await this.client.query("INSERT INTO notification_delivery_evidence(task_id,kind,evidence) VALUES($1,$2,$3)",
      [task.notificationTaskId, decision.allowed ? "QuotaReserved" : "QuotaDeferred", JSON.stringify({ channel: channels[0].channel, ...decision })]);
  }
}

export async function claimDelivery(pool: Pool): Promise<DeliveryClaim | undefined> {
  return withTransaction(pool, async client => {
    const result = await client.query<QueueRow>(
      "SELECT * FROM notification_delivery_queue WHERE state='Ready' AND available_at<=now() ORDER BY available_at,task_id LIMIT 1 FOR UPDATE SKIP LOCKED",
    );
    const row = result.rows[0];
    if (!row) return undefined;
    const token = randomUUID();
    await client.query("UPDATE notification_delivery_queue SET state='Claimed',claim_token=$2,lease_until=now()+$3*interval '1 second',attempt_number=attempt_number+1,updated_at=now() WHERE task_id=$1",
      [row.task_id, token, CLAIM_LEASE_SECONDS]);
    await client.query("INSERT INTO notification_delivery_attempts(claim_token,task_id,attempt_number,channel,state,message_id) VALUES($1,$2,$3,$4,'Claimed',$5)",
      [token, row.task_id, row.attempt_number + 1, row.channels[row.channel_index].channel, row.message_id]);
    await evidence(client, { ...row, claim_token: token }, "Claimed", { leaseSeconds: CLAIM_LEASE_SECONDS });
    return { taskId: row.task_id, token };
  });
}

export async function beginDelivery(pool: Pool, claim: DeliveryClaim): Promise<NotificationTaskSnapshot | undefined> {
  return withTransaction(pool, async client => {
    const result = await client.query<QueueRow>("SELECT * FROM notification_delivery_queue WHERE task_id=$1 AND claim_token=$2 AND state='Claimed' AND lease_until>now() FOR UPDATE", [claim.taskId, claim.token]);
    const row = result.rows[0];
    if (!row) return undefined;
    const repository = new PostgresNotificationTaskRepository(client);
    const record = await repository.get(row.task_id);
    if (!record) throw new Error("Delivery task missing");
    const channel = row.channels[row.channel_index];
    await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1,0))", [record.data.recipientRef]);
    const decision = await new PostgresRateLimitRepository(client).reserve(row.task_id, record.data.recipientRef, channel.channel, new Date());
    if (!decision.allowed) {
      await client.query("UPDATE notification_delivery_queue SET state='Ready',available_at=$2,claim_token=NULL,lease_until=NULL,updated_at=now() WHERE task_id=$1", [row.task_id, decision.retryAfter]);
      await client.query("UPDATE notification_delivery_attempts SET state='QuotaDeferred',completed_at=now() WHERE claim_token=$1", [claim.token]);
      await evidence(client, row, "QuotaDeferred", { channel: channel.channel, retryAt: decision.retryAfter });
      return undefined;
    }
    let task = NotificationTask.restore(record.data);
    if (task.status === "Planned" || task.status === "Authorized") {
      const dispatched = task.dispatch({ notificationTaskId: task.id, dispatchedAt: new Date() });
      await repository.save(dispatched.task.toSnapshot(), record.version);
      await new OutboxAppender(client).append(toEventEnvelope(dispatched.event), "events:notification");
      task = dispatched.task;
    }
    await client.query("UPDATE notification_delivery_queue SET state='Sending',lease_until=now()+$2*interval '1 second',updated_at=now() WHERE task_id=$1", [row.task_id, CLAIM_LEASE_SECONDS]);
    await client.query("UPDATE notification_delivery_attempts SET state='Sending',started_at=clock_timestamp() WHERE claim_token=$1", [claim.token]);
    await evidence(client, row, "SendStarted", { channel: channel.channel, messageId: row.message_id });
    return { ...task.toSnapshot(), channel: channel.channel, variables: channel.variables };
  });
}

export async function recordDeliveryResult(pool: Pool, claim: DeliveryClaim, result: DeliveryResult): Promise<void> {
  await withTransaction(pool, async client => {
    const found = await client.query<QueueRow>("SELECT * FROM notification_delivery_queue WHERE task_id=$1 FOR UPDATE", [claim.taskId]);
    const row = found.rows[0];
    if (!row) throw new Error("Delivery queue entry missing");
    const attempt = await client.query("SELECT state FROM notification_delivery_attempts WHERE claim_token=$1 AND task_id=$2", [claim.token, claim.taskId]);
    if (!attempt.rows[0]) throw new Error("Delivery attempt missing");
    if (!["Sending", "Unknown"].includes(attempt.rows[0].state)) return;
    if (row.claim_token !== claim.token || !["Sending", "Unknown"].includes(row.state)) throw new Error("Delivery claim no longer owns task");
    if (row.state === "Unknown" && !result.ok && result.outcome === "Unknown") return;
    const channel = row.channels[row.channel_index].channel;
    const attempts = await client.query("SELECT count(*)::int AS n FROM notification_delivery_attempts WHERE task_id=$1 AND channel=$2 AND started_at IS NOT NULL", [claim.taskId, channel]);
    const retry = !result.ok && result.outcome !== "Unknown" && result.retryable === true && attempts.rows[0].n < MAX_CHANNEL_ATTEMPTS;
    const fallback = !result.ok && result.outcome !== "Unknown" && !retry && row.channel_index + 1 < row.channels.length;
    const retryAt = retry ? new Date(Date.now() + 1000 * 2 ** (attempts.rows[0].n - 1)) : undefined;
    const state = result.ok ? "Delivered" : result.outcome === "Unknown" ? "Unknown" : retry || fallback ? "Ready" : "Failed";
    const repository = new PostgresNotificationTaskRepository(client);
    const record = await repository.get(row.task_id);
    if (!record) throw new Error("Delivery task missing");
    const recorded = NotificationTask.restore(record.data).recordReceipt({
      receiptId: newReceiptId(), notificationTaskId: row.task_id, channel,
      outcome: result.ok ? "Delivered" : result.outcome, recordedAt: new Date(),
      finalFailure: state === "Failed", attemptId: claim.token, messageId: row.message_id, retryAt,
      ...(result.ok ? { providerMessage: result.providerResponse } : { providerCode: result.providerCode, providerMessage: result.providerMessage }),
    });
    await repository.save(recorded.task.toSnapshot(), record.version);
    await new OutboxAppender(client).append(toEventEnvelope(recorded.event), "events:notification");
    await client.query("UPDATE notification_delivery_attempts SET state=$2,completed_at=clock_timestamp(),result=$3 WHERE claim_token=$1",
      [claim.token, result.ok ? "Delivered" : result.outcome === "Unknown" ? "Unknown" : "Failed", JSON.stringify(result)]);
    await evidence(client, row, row.state === "Unknown" ? "LateResult" : "SendResult", { ...result, nextState: state, retryAt, fallback });
    await client.query("UPDATE notification_delivery_queue SET state=$2,channel_index=channel_index+$3,available_at=$4,lease_until=NULL,updated_at=now() WHERE task_id=$1",
      [row.task_id, state, fallback ? 1 : 0, retryAt ?? new Date()]);
  });
}

export async function recoverDeliveryClaims(pool: Pool): Promise<void> {
  for (let index = 0; index < 32; index++) {
    const recovered = await withTransaction(pool, async client => {
      const found = await client.query<QueueRow>("SELECT * FROM notification_delivery_queue WHERE state IN ('Claimed','Sending') AND lease_until<=now() ORDER BY lease_until,task_id LIMIT 1 FOR UPDATE SKIP LOCKED");
      const row = found.rows[0];
      if (!row) return false;
      if (row.state === "Claimed") {
        await client.query("UPDATE notification_delivery_queue SET state='Ready',claim_token=NULL,lease_until=NULL,updated_at=now() WHERE task_id=$1", [row.task_id]);
        await client.query("UPDATE notification_delivery_attempts SET state='AbandonedBeforeSend',completed_at=now() WHERE claim_token=$1", [row.claim_token]);
        await evidence(client, row, "ClaimExpiredBeforeSend", {});
      } else {
        const repository = new PostgresNotificationTaskRepository(client);
        const record = await repository.get(row.task_id);
        if (!record) throw new Error("Delivery task missing");
        const result = { ok: false, outcome: "Unknown", providerCode: "SEND_LEASE_EXPIRED" } as const;
        const recorded = NotificationTask.restore(record.data).recordReceipt({
          receiptId: newReceiptId(), notificationTaskId: row.task_id, channel: row.channels[row.channel_index].channel,
          outcome: "Unknown", providerCode: result.providerCode, recordedAt: new Date(), attemptId: row.claim_token, messageId: row.message_id,
        });
        await repository.save(recorded.task.toSnapshot(), record.version);
        await new OutboxAppender(client).append(toEventEnvelope(recorded.event), "events:notification");
        await client.query("UPDATE notification_delivery_queue SET state='Unknown',lease_until=NULL,updated_at=now() WHERE task_id=$1", [row.task_id]);
        await client.query("UPDATE notification_delivery_attempts SET state='Unknown',completed_at=now(),result=$2 WHERE claim_token=$1", [row.claim_token, JSON.stringify(result)]);
        await evidence(client, row, "SendLeaseExpired", result);
      }
      return true;
    });
    if (!recovered) return;
  }
}

export async function sendClaim(pool: Pool, gateway: NotificationChannelGateway, claim: DeliveryClaim): Promise<void> {
  const task = await beginDelivery(pool, claim);
  if (!task) return;
  const persist = async (result: DeliveryResult) => {
    for (let attempt = 0; ; attempt++) {
      try { await recordDeliveryResult(pool, claim, result); return; }
      catch (error) {
        if (attempt >= 4) throw error;
        await new Promise(resolve => setTimeout(resolve, 100 * 2 ** attempt));
      }
    }
  };
  let result: DeliveryResult;
  try { result = await gateway.send(task, persist); }
  catch (error) {
    const details = deliveryErrorEvidence(error);
    result = { ok: false, outcome: "Unknown", providerCode: details.code ?? "GATEWAY_EXCEPTION", providerMessage: JSON.stringify(details) };
  }
  await persist(result);
}

export function startDeliveryWorker(pool: Pool, gateway: NotificationChannelGateway, concurrency = Number(process.env.SMTP_MAX_CONNECTIONS ?? "8")): { stop(): Promise<void> } {
  if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 128) throw new Error("Invalid delivery concurrency");
  let stopped = false;
  const run = async () => {
    while (!stopped) {
      try {
        const claim = await claimDelivery(pool);
        if (claim) { await sendClaim(pool, gateway, claim); continue; }
      } catch (error) { console.error({ component: "notification-delivery", code: "WORKER_CYCLE_FAILED", error: deliveryErrorEvidence(error) }); }
      await new Promise(resolve => setTimeout(resolve, 250));
    }
  };
  const workers = Array.from({ length: concurrency }, run);
  const recovery = (async () => {
    while (!stopped) {
      try { await recoverDeliveryClaims(pool); }
      catch (error) { console.error({ component: "notification-delivery", code: "RECOVERY_CYCLE_FAILED", error: deliveryErrorEvidence(error) }); }
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
  })();
  return { stop: async () => { stopped = true; await Promise.all([...workers, recovery]); } };
}
