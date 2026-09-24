import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import { createServer, connect, type Socket } from "node:net";
import { once } from "node:events";
import { test } from "node:test";
import { Pool } from "pg";
import { withTransaction } from "@trainticket/ts-kit";
import { NotificationTask, newNotificationTaskId, type NotificationTaskSnapshot } from "./domain.js";
import { createSmtpTransport, SmtpChannelGateway, smtpMessageId } from "./adapters/channel-gateways.js";
import { PostgresNotificationTaskRepository } from "./adapters/storage/notification-repository.js";
import { PostgresDeliveryScheduler, beginDelivery, claimDelivery, recordDeliveryResult, recoverDeliveryClaims, sendClaim, startDeliveryWorker } from "./adapters/storage/delivery-queue.js";
import { deliverOrDefer, getNotificationTrail } from "./adapters/storage/runtime.js";
import { createApp } from "./app.js";
import type { EventEnvelope } from "@trainticket/ts-kit";

const enabled = Boolean(process.env.TEST_DATABASE_URL && process.env.TEST_SMTP_URL && process.env.TEST_MAILPIT_URL && process.env.TEST_NOTIFICATION_TASKS);
const pause = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

async function until(predicate: () => Promise<boolean>, timeout = 10000): Promise<void> {
  const deadline = Date.now() + timeout;
  while (!await predicate()) {
    assert.ok(Date.now() < deadline, "Condition did not become true");
    await pause(20);
  }
}

test("durable delivery with recorded business tasks, PostgreSQL and Mailpit", { skip: !enabled }, async t => {
  for (const value of [process.env.TEST_DATABASE_URL!, process.env.TEST_SMTP_URL!, process.env.TEST_MAILPIT_URL!]) {
    assert.ok(["127.0.0.1", "localhost"].includes(new URL(value).hostname), "Live tests require local endpoints");
  }
  const recorded = JSON.parse(await readFile(process.env.TEST_NOTIFICATION_TASKS!, "utf8")) as NotificationTaskSnapshot[];
  assert.equal(recorded.length, 32);
  assert.ok(recorded.every(task => task.channel === "EMAIL"));
  const schema = `notification_delivery_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL, options: "-c statement_timeout=5000" });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 48, options: `-c search_path=${schema} -c statement_timeout=5000`, application_name: schema });
  const transport = createSmtpTransport(process.env.TEST_SMTP_URL!, 32);
  const gateway = new SmtpChannelGateway(transport);
  const enqueue = async (task: NotificationTaskSnapshot, targetPool = pool) => withTransaction(targetPool, async client => {
    await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1,0))", [task.recipientRef]);
    const scheduled = NotificationTask.schedule({ ...task, notificationTaskId: newNotificationTaskId(), scheduledAt: new Date() });
    await new PostgresNotificationTaskRepository(client).saveNew(scheduled.task.toSnapshot());
    await new PostgresDeliveryScheduler(client).enqueue(scheduled.task.toSnapshot(), [{ channel: task.channel, variables: task.variables }]);
    return scheduled.task.id;
  });
  const mail = async (taskId: string): Promise<{ total: number; messages: { ID: string; MessageID: string }[] }> => {
    const response = await fetch(`${process.env.TEST_MAILPIT_URL}/api/v1/search?query=${encodeURIComponent(`message-id:${smtpMessageId(taskId).slice(1, -1)}`)}`);
    assert.equal(response.status, 200);
    const result = await response.json() as { messages_count: number; messages: { ID: string; MessageID: string }[] };
    return { total: result.messages_count, messages: result.messages };
  };
  const snapshot = async (id: string) => (await pool.query("SELECT data FROM notification_task_snapshots WHERE id=$1", [id])).rows[0].data as NotificationTaskSnapshot;
  try {
    const chaos = await fetch(`${process.env.TEST_MAILPIT_URL}/api/v1/chaos`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ Sender: { ErrorCode: 451, Probability: 0 }, Recipient: { ErrorCode: 451, Probability: 0 }, Authentication: { ErrorCode: 535, Probability: 0 } }) });
    assert.equal(chaos.status, 200);
    for (const migration of (await readdir("migrations")).filter(name => name.endsWith(".sql")).sort()) await pool.query(await readFile(`migrations/${migration}`, "utf8"));

    await t.test("enqueue commits without SMTP and competing workers deliver exactly once", async () => {
      const taskId = await enqueue(recorded[0]);
      assert.equal((await mail(taskId)).total, 0);
      assert.equal((await snapshot(taskId)).status, "Planned");
      const claims = (await Promise.all(Array.from({ length: 32 }, () => claimDelivery(pool)))).filter(claim => claim !== undefined);
      assert.equal(claims.length, 1);
      await Promise.all(Array.from({ length: 16 }, () => sendClaim(pool, gateway, claims[0])));
      assert.equal((await mail(taskId)).total, 1);
      assert.equal((await snapshot(taskId)).status, "Delivered");
      assert.equal((await snapshot(taskId)).receipts.length, 1);
      assert.equal((await pool.query("SELECT count(*)::int AS n FROM notification_rate_limits WHERE task_id=$1", [taskId])).rows[0].n, 1);
      await assert.rejects(enqueue(recorded[0]));
      assert.equal((await pool.query("SELECT count(*)::int AS n FROM notification_delivery_queue")).rows[0].n, 1);
    });

    await t.test("claim expiry before sending fences the old worker", async () => {
      const taskId = await enqueue(recorded[1]);
      const first = (await claimDelivery(pool))!;
      await pool.query("UPDATE notification_delivery_queue SET lease_until=now()-interval '1 second' WHERE task_id=$1", [taskId]);
      await recoverDeliveryClaims(pool);
      assert.equal(await beginDelivery(pool, first), undefined);
      const second = (await claimDelivery(pool))!;
      await sendClaim(pool, gateway, second);
      assert.equal((await mail(taskId)).total, 1);
      assert.deepEqual((await pool.query("SELECT state FROM notification_delivery_attempts WHERE task_id=$1 ORDER BY attempt_number", [taskId])).rows.map(row => row.state), ["AbandonedBeforeSend", "Delivered"]);
    });

    await t.test("lost result after actual SMTP acceptance stays Unknown without resending", async () => {
      const taskId = await enqueue(recorded[2]);
      const claim = (await claimDelivery(pool))!;
      const task = (await beginDelivery(pool, claim))!;
      const result = await gateway.send(task);
      assert.equal(result.ok, true);
      assert.equal((await mail(taskId)).total, 1);
      await pool.query("UPDATE notification_delivery_queue SET lease_until=now()-interval '1 second' WHERE task_id=$1", [taskId]);
      await recoverDeliveryClaims(pool);
      assert.equal((await snapshot(taskId)).status, "Unknown");
      const app = createApp(undefined, { ready: () => true, getNotificationTrail: id => getNotificationTrail(pool, id) });
      const address = await app.listen({ host: "127.0.0.1", port: 0 });
      try {
        const response = await fetch(`${address}/api/v1/notifications/${taskId}/trail`);
        assert.equal(response.status, 200);
        const trail = await response.json() as { status: string; attempts: { status: string }[]; delivery: { state: string; evidence: unknown[] } };
        assert.equal(trail.status, "Unknown");
        assert.equal(trail.delivery.state, "Unknown");
        assert.equal(trail.attempts[0].status, "UNKNOWN");
        assert.ok(trail.delivery.evidence.length >= 3);
      } finally { await app.close(); }
      assert.equal(await claimDelivery(pool), undefined);
      await sendClaim(pool, gateway, claim);
      assert.equal((await mail(taskId)).total, 1);
      await recordDeliveryResult(pool, claim, result);
      await recordDeliveryResult(pool, claim, result);
      assert.deepEqual((await snapshot(taskId)).receipts.map(receipt => receipt.outcome), ["Unknown", "Delivered"]);
    });

    await t.test("actual connection refusal retries with the same message ID and one quota reservation", async () => {
      const taskId = await enqueue(recorded[3]);
      const server = createServer();
      server.listen(0, "127.0.0.1");
      await once(server, "listening");
      const port = (server.address() as { port: number }).port;
      await new Promise<void>(resolve => server.close(() => resolve()));
      const refused = createSmtpTransport(`smtp://127.0.0.1:${port}`, 32);
      try { await sendClaim(pool, new SmtpChannelGateway(refused), (await claimDelivery(pool))!); }
      finally { refused.close(); }
      assert.equal((await snapshot(taskId)).receipts[0].outcome, "Timeout");
      assert.match((await snapshot(taskId)).receipts[0].providerMessage!, /Connection refused/);
      assert.equal((await mail(taskId)).total, 0);
      await until(async () => (await pool.query("SELECT available_at<=now() AS due FROM notification_delivery_queue WHERE task_id=$1", [taskId])).rows[0].due);
      await sendClaim(pool, gateway, (await claimDelivery(pool))!);
      assert.equal((await mail(taskId)).total, 1);
      assert.deepEqual((await snapshot(taskId)).receipts.map(receipt => receipt.outcome), ["Timeout", "Delivered"]);
      assert.equal(new Set((await snapshot(taskId)).receipts.map(receipt => receipt.messageId)).size, 1);
      assert.equal((await pool.query("SELECT count(*)::int AS n FROM notification_rate_limits WHERE task_id=$1", [taskId])).rows[0].n, 1);
    });

    await t.test("real delayed SMTP holds no database connection or recipient lock and records late acceptance", async () => {
      const taskId = await enqueue(recorded[4]);
      const sockets = new Set<Socket>();
      const target = new URL(process.env.TEST_SMTP_URL!);
      let connected = false;
      const timers = new Set<NodeJS.Timeout>();
      const server = createServer(client => {
        const upstream = connect(Number(target.port), target.hostname);
        sockets.add(client); sockets.add(upstream);
        client.pipe(upstream);
        upstream.on("data", data => {
          const timer = setTimeout(() => { timers.delete(timer); if (!client.destroyed) client.write(data); }, 1100);
          timers.add(timer);
        });
        connected = true;
        client.on("error", () => upstream.destroy());
        upstream.on("error", () => client.destroy());
      });
      server.listen(0, "127.0.0.1");
      await once(server, "listening");
      const delayed = createSmtpTransport(`smtp://127.0.0.1:${(server.address() as { port: number }).port}`, 32);
      try {
        const sending = sendClaim(pool, new SmtpChannelGateway(delayed), (await claimDelivery(pool))!);
        await until(async () => connected);
        assert.equal(pool.waitingCount, 0);
        assert.equal(pool.idleCount, pool.totalCount);
        await withTransaction(pool, async client => {
          const lock = await client.query("SELECT pg_try_advisory_xact_lock(hashtextextended($1,0)) AS acquired", [recorded[4].recipientRef]);
          assert.equal(lock.rows[0].acquired, true);
        });
        await sending;
        assert.equal((await snapshot(taskId)).status, "Unknown");
        assert.equal(await claimDelivery(pool), undefined);
        await until(async () => (await snapshot(taskId)).status === "Delivered");
        assert.equal((await mail(taskId)).total, 1);
        assert.deepEqual((await snapshot(taskId)).receipts.map(receipt => receipt.outcome), ["Unknown", "Delivered"]);
      } finally {
        delayed.close();
        for (const timer of timers) clearTimeout(timer);
        for (const socket of sockets) socket.destroy();
        await new Promise<void>(resolve => server.close(() => resolve()));
      }
    });

    await t.test("unchanged recorded workload uses 32 SMTP workers and persists every receipt", async () => {
      const burstSchema = `${schema}_burst`;
      await admin.query(`CREATE SCHEMA ${burstSchema}`);
      const burstPool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 48, options: `-c search_path=${burstSchema} -c statement_timeout=5000` });
      try {
        for (const migration of (await readdir("migrations")).filter(name => name.endsWith(".sql")).sort()) await burstPool.query(await readFile(`migrations/${migration}`, "utf8"));
        const ids = await Promise.all(recorded.map(task => enqueue(task, burstPool)));
        assert.equal(ids.length, 32);
        const worker = startDeliveryWorker(burstPool, gateway, 32);
        try {
          await until(async () => (await burstPool.query("SELECT count(*)::int AS n FROM notification_delivery_queue WHERE task_id=ANY($1) AND state='Delivered'", [ids])).rows[0].n === 32, 20000);
        } finally { await worker.stop(); }
        for (const id of ids) assert.equal((await mail(id)).total, 1);
        assert.equal((await burstPool.query("SELECT count(*)::int AS n FROM notification_rate_limits")).rows[0].n, 32);
        assert.equal((await burstPool.query("SELECT count(*)::int AS n FROM notification_delivery_attempts")).rows[0].n, 32);
      } finally { await burstPool.end(); }
    });

    await t.test("real SMTP interruption after acceptance retains Unknown without automatic resend", async () => {
      const taskId = await enqueue(recorded[5]);
      const target = new URL(process.env.TEST_SMTP_URL!);
      const sockets = new Set<Socket>();
      let disconnect: (() => void) | undefined;
      const server = createServer(client => {
        const upstream = connect(Number(target.port), target.hostname);
        sockets.add(client); sockets.add(upstream);
        client.pipe(upstream);
        upstream.on("data", data => {
          if (data.toString().includes("queued as")) {
            disconnect = () => { client.destroy(); upstream.destroy(); };
          } else client.write(data);
        });
        client.on("error", () => upstream.destroy());
        upstream.on("error", () => client.destroy());
      });
      server.listen(0, "127.0.0.1");
      await once(server, "listening");
      const interrupted = createSmtpTransport(`smtp://127.0.0.1:${(server.address() as { port: number }).port}`, 32);
      try {
        const sending = sendClaim(pool, new SmtpChannelGateway(interrupted), (await claimDelivery(pool))!);
        await until(async () => Boolean(disconnect));
        assert.equal((await mail(taskId)).total, 1);
        disconnect!();
        await sending;
        assert.equal((await snapshot(taskId)).status, "Unknown");
        assert.equal(await claimDelivery(pool), undefined);
        assert.equal((await mail(taskId)).total, 1);
        assert.match((await snapshot(taskId)).receipts[0].providerMessage!, /Connection interrupted/);
      } finally {
        interrupted.close();
        for (const socket of sockets) socket.destroy();
        await new Promise<void>(resolve => server.close(() => resolve()));
      }
    });

    await t.test("a real late result persisted first cannot be overwritten by the earlier deadline result", async () => {
      const taskId = await enqueue(recorded[6]);
      const claim = (await claimDelivery(pool))!;
      const task = (await beginDelivery(pool, claim))!;
      const target = new URL(process.env.TEST_SMTP_URL!);
      const sockets = new Set<Socket>();
      const timers = new Set<NodeJS.Timeout>();
      const server = createServer(client => {
        const upstream = connect(Number(target.port), target.hostname);
        sockets.add(client); sockets.add(upstream);
        client.pipe(upstream);
        upstream.on("data", data => {
          const timer = setTimeout(() => { timers.delete(timer); if (!client.destroyed) client.write(data); }, 1100);
          timers.add(timer);
        });
        client.on("error", () => upstream.destroy());
        upstream.on("error", () => client.destroy());
      });
      server.listen(0, "127.0.0.1");
      await once(server, "listening");
      const delayed = createSmtpTransport(`smtp://127.0.0.1:${(server.address() as { port: number }).port}`, 32);
      try {
        const unknown = await new SmtpChannelGateway(delayed).send(task, result => recordDeliveryResult(pool, claim, result));
        assert.equal(unknown.ok, false);
        assert.equal(unknown.ok ? undefined : unknown.outcome, "Unknown");
        await until(async () => (await snapshot(taskId)).status === "Delivered");
        await recordDeliveryResult(pool, claim, unknown);
        assert.equal((await snapshot(taskId)).status, "Delivered");
        assert.equal((await snapshot(taskId)).receipts.length, 1);
        assert.equal((await mail(taskId)).total, 1);
      } finally {
        delayed.close();
        for (const timer of timers) clearTimeout(timer);
        for (const socket of sockets) socket.destroy();
        await new Promise<void>(resolve => server.close(() => resolve()));
      }
    });

    await t.test("Mailpit SMTP rejection persists failure and bounded retry evidence", async () => {
      const taskId = await enqueue(recorded[7]);
      const chaos = async (probability: number) => {
        const response = await fetch(`${process.env.TEST_MAILPIT_URL}/api/v1/chaos`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ Sender: { ErrorCode: 451, Probability: probability }, Recipient: { ErrorCode: 451, Probability: 0 }, Authentication: { ErrorCode: 535, Probability: 0 } }) });
        assert.equal(response.status, 200);
      };
      await chaos(100);
      try {
        for (let index = 0; index < 3; index++) {
          await until(async () => (await pool.query("SELECT available_at<=now() AS due FROM notification_delivery_queue WHERE task_id=$1", [taskId])).rows[0].due);
          await sendClaim(pool, gateway, (await claimDelivery(pool))!);
        }
        assert.equal((await snapshot(taskId)).status, "Failed");
        assert.equal((await snapshot(taskId)).receipts.length, 3);
        assert.equal((await mail(taskId)).total, 0);
        assert.equal((await pool.query("SELECT count(*)::int AS n FROM notification_rate_limits WHERE task_id=$1", [taskId])).rows[0].n, 1);
      } finally { await chaos(0); }
    });

    if (process.env.TEST_MULTI_RECIPIENT_EVENT) await t.test("recorded source event deduplication persists the queue before sending", async () => {
      const event = JSON.parse(await readFile(process.env.TEST_MULTI_RECIPIENT_EVENT!, "utf8")) as EventEnvelope;
      const intake = () => withTransaction(pool, client => deliverOrDefer(client, event, "events:transfer-management", gateway));
      await intake();
      const before = (await pool.query("SELECT id,data FROM notification_task_snapshots WHERE data->>'triggerEventId'=$1", [event.eventId])).rows;
      assert.ok(before.length > 0);
      assert.ok(before.every(row => row.data.status === "Planned"));
      await Promise.all(Array.from({ length: 16 }, intake));
      assert.equal((await pool.query("SELECT id FROM notification_task_snapshots WHERE data->>'triggerEventId'=$1", [event.eventId])).rowCount, before.length);
      const worker = startDeliveryWorker(pool, gateway, 32);
      try { await until(async () => (await pool.query("SELECT count(*)::int AS n FROM notification_delivery_queue WHERE task_id=ANY($1) AND state='Delivered'", [before.map(row => row.id)])).rows[0].n === before.length); }
      finally { await worker.stop(); }
    });
    t.diagnostic(`Retained isolated PostgreSQL schema ${schema}; SMTP messages and attempt evidence remain available locally.`);
  } finally {
    transport.close();
    await pool.end();
    await admin.end();
  }
});
