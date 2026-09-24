import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import { test } from "node:test";
import { Pool } from "pg";
import nodemailer from "nodemailer";
import { withTransaction, type EventEnvelope } from "@trainticket/ts-kit";
import { SmtpChannelGateway } from "./adapters/channel-gateways.js";
import { claimDelivery, sendClaim } from "./adapters/storage/delivery-queue.js";
import { deliverOrDefer, isDuplicateBusinessNotification } from "./adapters/storage/runtime.js";
import { notificationRecipients } from "./application/notification-service.js";

test("each recorded traveler receives one notification across quota deferral and retries", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_SMTP_URL || !process.env.TEST_MULTI_RECIPIENT_EVENT,
}, async () => {
  const event = JSON.parse(await readFile(process.env.TEST_MULTI_RECIPIENT_EVENT!, "utf8")) as EventEnvelope;
  const recipients = [...notificationRecipients(event)].sort();
  assert.equal(recipients.length, 2);
  const schema = `notification_multi_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, options: `-c search_path=${schema}` });
  const transport = nodemailer.createTransport(process.env.TEST_SMTP_URL!);
  const gateway = new SmtpChannelGateway(transport);
  try {
    for (const file of (await readdir("migrations")).filter(name => name.endsWith(".sql")).sort()) {
      await pool.query(await readFile(`migrations/${file}`, "utf8"));
    }
    await pool.query("INSERT INTO notification_rate_limits(recipient_ref,channel,occurred_at) SELECT $1,'EMAIL',now() FROM generate_series(1,20)", [recipients[1]]);
    assert.equal(await withTransaction(pool, client => deliverOrDefer(client,event,"events:transfer-management",gateway)), "ack");
    const first = await pool.query("SELECT id,version,data FROM notification_task_snapshots ORDER BY data->>'recipientRef'");
    assert.equal(first.rowCount,2);
    assert.deepEqual(first.rows.map(row=>row.data.recipientRef),recipients);
    assert.ok(first.rows.every(row=>row.data.status==="Planned"));
    const deferred=await pool.query("SELECT 1 FROM notification_delivery_queue queue JOIN notification_task_snapshots task ON task.id=queue.task_id WHERE task.data->>'recipientRef'=$1 AND queue.state='Ready' AND queue.available_at>now()",[recipients[1]]);
    assert.equal(deferred.rowCount,1);
    assert.equal(await isDuplicateBusinessNotification(pool,event),true);
    await withTransaction(pool,client => deliverOrDefer(client,event,"events:transfer-management",gateway));
    assert.equal((await pool.query("SELECT count(*)::int AS n FROM notification_task_snapshots")).rows[0].n,2);
    const deliverDue=async()=>{for(;;){const claim=await claimDelivery(pool);if(!claim)return;await sendClaim(pool,gateway,claim);}};
    await deliverDue();
    const pending=await pool.query("SELECT data->>'status' AS status FROM notification_task_snapshots WHERE data->>'recipientRef'=$1",[recipients[1]]);
    assert.equal(pending.rows[0].status,"Planned");
    await pool.query("UPDATE notification_rate_limits SET occurred_at=now()-interval '25 hours' WHERE recipient_ref=$1",[recipients[1]]);
    await pool.query("UPDATE notification_delivery_queue SET available_at=now() WHERE state='Ready'");
    await deliverDue();
    assert.equal(await isDuplicateBusinessNotification(pool,event),true);
    const delivered=await pool.query("SELECT id,version,data FROM notification_task_snapshots ORDER BY data->>'recipientRef'");
    assert.equal(delivered.rowCount,2);
    assert.deepEqual(delivered.rows.map(row=>row.data.recipientRef),recipients);
    assert.ok(delivered.rows.every(row=>row.data.status==="Delivered"));
    assert.deepEqual(delivered.rows.map(row=>row.id),first.rows.map(row=>row.id));
    assert.equal((await pool.query("SELECT count(*)::int AS n FROM notification_deferred")).rows[0].n,0);
    await withTransaction(pool,client=>deliverOrDefer(client,event,"events:transfer-management",gateway));
    assert.equal((await pool.query("SELECT count(*)::int AS n FROM outbox WHERE envelope->>'eventType'='NotificationDelivered'")).rows[0].n,2);
  } finally {
    transport.close();
    await pool.end();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});
