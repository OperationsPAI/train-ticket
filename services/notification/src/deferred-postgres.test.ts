import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import { it } from "node:test";
import { Pool } from "pg";
import { createEventEnvelope, withTransaction } from "@trainticket/ts-kit";
import { notificationChannelGatewayFromEnv } from "./adapters/channel-gateways.js";
import { claimDelivery, sendClaim } from "./adapters/storage/delivery-queue.js";
import { deliverOrDefer } from "./adapters/storage/runtime.js";
import { PostgresNotificationAggregator } from "./adapters/storage/notification-repository.js";

it("defers a rate limited notification in the delivery queue and sends it after the limit expires", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_SMTP_URL,
}, async () => {
  const schema = `deferred_test_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, options: `-c search_path=${schema}` });
  try {
    for (const migration of (await readdir("migrations")).filter((name) => name.endsWith(".sql")).sort()) {
      await pool.query(await readFile(`migrations/${migration}`, "utf8"));
    }
    const recipient = `acc-${randomUUID()}`;
    const order = `ord-${randomUUID()}`;
    const candidate = { recipientRef: recipient, orderRef: order, templateType: "ORDER_CONFIRMED" as const, occurredAt: new Date() };
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      assert.equal(await new PostgresNotificationAggregator(client).shouldSuppress(candidate), false);
      await client.query("ROLLBACK");
    } finally { client.release(); }
    assert.equal((await pool.query("SELECT count(*)::int AS n FROM notification_aggregation")).rows[0].n, 0);
    await pool.query("INSERT INTO notification_rate_limits(recipient_ref,channel,occurred_at) SELECT $1,'EMAIL',now() FROM generate_series(1,20)", [recipient]);
    const event = createEventEnvelope({ producer: "journey-order", eventType: "JourneyOrderConfirmed", payload: {
      accountId: recipient, orderId: order, origin: "Beijing", destination: "Shanghai", departureTime: new Date().toISOString(),
      monetarySummary: { currency: "CNY", total: 10000 }, confirmedAt: new Date().toISOString(),
    } });
    const gateway = notificationChannelGatewayFromEnv({ SMTP_URL: process.env.TEST_SMTP_URL });
    assert.equal(await withTransaction(pool, (transaction) => deliverOrDefer(transaction, event, "events:journey-order", gateway)), "ack");
    const queued = await pool.query("SELECT state,available_at>now() AS deferred FROM notification_delivery_queue");
    assert.equal(queued.rowCount, 1);
    assert.equal(queued.rows[0].state, "Ready");
    assert.equal(queued.rows[0].deferred, true);
    assert.equal((await pool.query("SELECT count(*)::int AS n FROM notification_delivery_evidence WHERE kind='QuotaDeferred'")).rows[0].n, 1);
    assert.equal((await pool.query("SELECT count(*)::int AS n FROM notification_task_snapshots")).rows[0].n, 1);
    assert.equal((await pool.query("SELECT data->>'status' AS status FROM notification_task_snapshots")).rows[0].status, "Planned");
    assert.equal((await pool.query("SELECT count(*)::int AS n FROM notification_deferred")).rows[0].n, 0);
    assert.equal(await claimDelivery(pool), undefined);
    await pool.query("UPDATE notification_rate_limits SET occurred_at=now()-interval '25 hours'");
    await pool.query("UPDATE notification_delivery_queue SET available_at=now()");
    const claim = (await claimDelivery(pool))!;
    await sendClaim(pool, gateway, claim);
    assert.equal((await pool.query("SELECT data->>'status' AS status FROM notification_task_snapshots")).rows[0].status, "Delivered");
    assert.equal((await pool.query("SELECT count(*)::int AS n FROM notification_rate_limits WHERE task_id IS NOT NULL")).rows[0].n, 1);
    assert.equal((await pool.query("SELECT count(*)::int AS n FROM notification_deferred")).rows[0].n, 0);
    await withTransaction(pool, (transaction) => deliverOrDefer(transaction, event, "events:journey-order", gateway));
    assert.equal((await pool.query("SELECT count(*)::int AS n FROM notification_task_snapshots")).rows[0].n, 1);
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});
