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
const CLAIM_BATCH_SIZE = 64;
const MAX_CHANNEL_ATTEMPTS = 3;
type QueueRow = {
  task_id: string; channels: DeliveryChannel[]; channel_index: number; state: string;
  claim_token: string; attempt_number: number; message_id: string;
};
export type DeliveryClaim = Readonly<{ taskId: string; token: string }>;
export type DeliveryWorkerOperations = Readonly<{
  claim: (limit: number) => Promise<DeliveryClaim[]>;
  send: (claim: DeliveryClaim) => Promise<void>;
  recover: () => Promise<void>;
}>;

type ClaimInput = Readonly<{
  task_id: string;
  claim_token: string;
  attempt_number: number;
  channel: DeliveryChannel["channel"];
  message_id: string;
}>;

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

export async function claimDeliveries(pool: Pool, limit: number): Promise<DeliveryClaim[]> {
  if (!Number.isInteger(limit) || limit < 1 || limit > CLAIM_BATCH_SIZE) {
    throw new Error(`Delivery claim batch size must be an integer between 1 and ${CLAIM_BATCH_SIZE}`);
  }
  return withTransaction(pool, async client => {
    const result = await client.query<QueueRow>(
      "SELECT * FROM notification_delivery_queue WHERE state='Ready' AND available_at<=now() ORDER BY available_at,task_id LIMIT $1 FOR UPDATE SKIP LOCKED",
      [limit],
    );
    if (result.rows.length === 0) return [];

    const claims: ClaimInput[] = result.rows.map(row => {
      const channel = row.channels[row.channel_index]?.channel;
      if (!channel) throw new Error(`Delivery task ${row.task_id} has no current channel`);
      return {
        task_id: row.task_id,
        claim_token: randomUUID(),
        attempt_number: row.attempt_number + 1,
        channel,
        message_id: row.message_id,
      };
    });
    const claimPayload = JSON.stringify(claims);
    const updated = await client.query(
      `UPDATE notification_delivery_queue AS queue
       SET state='Claimed', claim_token=claim.claim_token,
           lease_until=now()+$2*interval '1 second',
           attempt_number=claim.attempt_number, updated_at=now()
       FROM jsonb_to_recordset($1::jsonb) AS claim(
         task_id text, claim_token uuid, attempt_number integer,
         channel text, message_id text
       )
       WHERE queue.task_id=claim.task_id
         AND queue.state='Ready' AND queue.available_at<=now()`,
      [claimPayload, CLAIM_LEASE_SECONDS],
    );
    if (updated.rowCount !== claims.length) {
      throw new Error(`Claimed ${updated.rowCount ?? 0} of ${claims.length} locked delivery tasks`);
    }
    await client.query(
      `INSERT INTO notification_delivery_attempts(
         claim_token,task_id,attempt_number,channel,state,message_id
       )
       SELECT claim_token,task_id,attempt_number,channel,'Claimed',message_id
       FROM jsonb_to_recordset($1::jsonb) AS claim(
         task_id text, claim_token uuid, attempt_number integer,
         channel text, message_id text
       )`,
      [claimPayload],
    );
    await client.query(
      `INSERT INTO notification_delivery_evidence(task_id,claim_token,kind,evidence)
       SELECT task_id,claim_token,'Claimed',$2::jsonb
       FROM jsonb_to_recordset($1::jsonb) AS claim(
         task_id text, claim_token uuid, attempt_number integer,
         channel text, message_id text
       )`,
      [claimPayload, JSON.stringify({ leaseSeconds: CLAIM_LEASE_SECONDS })],
    );
    return claims.map(claim => ({ taskId: claim.task_id, token: claim.claim_token }));
  });
}

export async function claimDelivery(pool: Pool): Promise<DeliveryClaim | undefined> {
  return (await claimDeliveries(pool, 1))[0];
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

export function startDeliveryWorker(
  pool: Pool,
  gateway: NotificationChannelGateway,
  concurrency = Number(process.env.SMTP_MAX_CONNECTIONS ?? "8"),
  operations: DeliveryWorkerOperations = {
    claim: limit => claimDeliveries(pool, limit),
    send: claim => sendClaim(pool, gateway, claim),
    recover: () => recoverDeliveryClaims(pool),
  },
): { stop(): Promise<void> } {
  if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 128) throw new Error("Invalid delivery concurrency");
  let stopped = false;
  const inFlight = new Set<Promise<void>>();
  const dispatch = (claim: DeliveryClaim): void => {
    let sending: Promise<void>;
    sending = operations.send(claim)
      .catch(error => console.error({ component: "notification-delivery", code: "WORKER_SEND_FAILED", error: deliveryErrorEvidence(error) }))
      .finally(() => inFlight.delete(sending));
    inFlight.add(sending);
  };
  const run = (async () => {
    while (!stopped) {
      try {
        const availableSlots = concurrency - inFlight.size;
        if (availableSlots > 0) {
          const claims = await operations.claim(Math.min(availableSlots, CLAIM_BATCH_SIZE));
          for (const claim of claims) dispatch(claim);
          if (claims.length > 0) continue;
        }
      } catch (error) { console.error({ component: "notification-delivery", code: "WORKER_CYCLE_FAILED", error: deliveryErrorEvidence(error) }); }
      if (inFlight.size > 0) await Promise.race(inFlight);
      else await new Promise(resolve => setTimeout(resolve, 250));
    }
    await Promise.all(inFlight);
  })();
  const recovery = (async () => {
    while (!stopped) {
      try { await operations.recover(); }
      catch (error) { console.error({ component: "notification-delivery", code: "RECOVERY_CYCLE_FAILED", error: deliveryErrorEvidence(error) }); }
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
  })();
  return { stop: async () => { stopped = true; await Promise.all([run, recovery]); } };
}
