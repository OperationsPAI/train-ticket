import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { it } from "node:test";
import { Pool } from "pg";
import { createEventEnvelope } from "@trainticket/ts-kit";
import { resolveRecipient } from "./adapters/storage/runtime.js";
import type { EventEnvelope } from "@trainticket/ts-kit";

it("resolves payment and refund recipients from persisted business references", { skip: !process.env.TEST_DATABASE_URL }, async () => {
  const schema = `notification_test_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, options: `-c search_path=${schema}` });
  try {
    await pool.query(await readFile("migrations/004_recipient_refs.sql", "utf8"));
    const account = `acc-${randomUUID()}`;
    const order = `ord-${randomUUID()}`;
    const payment = `pi-${randomUUID()}`;
    await resolveRecipient(pool, createEventEnvelope({ producer: "payment", eventType: "PaymentIntentCreated", payload: {
      paymentIntentId: payment, businessRef: order, payerRef: account,
    } }));
    const captured = await resolveRecipient(pool, createEventEnvelope({ producer: "payment", eventType: "PaymentCaptured", payload: {
      paymentIntentId: payment, businessRef: order,
    } }));
    assert.equal(captured.payload.accountId, account);
    const refund = await resolveRecipient(pool, createEventEnvelope({ producer: "payment", eventType: "RefundSettled", payload: {
      paymentIntentId: payment, refundId: `ref-${randomUUID()}`,
    } }));
    assert.equal(refund.payload.accountId, account);
    const count = await pool.query("SELECT count(*) FROM notification_recipient_refs");
    assert.equal(Number(count.rows[0].count), 2);
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});

it("recipient references share the caller transaction for recorded payment events", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_PAYMENT_EVENT,
}, async () => {
  const event = JSON.parse(await readFile(process.env.TEST_PAYMENT_EVENT!, "utf8")) as EventEnvelope;
  const schema = `notification_atomic_${randomUUID().replaceAll("-", "")}`;
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL });
  const client = await pool.connect();
  try {
    await client.query(`CREATE SCHEMA ${schema}`);
    await client.query(`SET search_path TO ${schema}`);
    await client.query(await readFile("migrations/004_recipient_refs.sql", "utf8"));
    await client.query("BEGIN");
    const resolved = await resolveRecipient(client, event);
    assert.equal(resolved.payload.accountId,event.payload.payerRef);
    assert.equal(Number((await client.query("SELECT count(*) FROM notification_recipient_refs")).rows[0].count),2);
    await client.query("ROLLBACK");
    assert.equal(Number((await client.query("SELECT count(*) FROM notification_recipient_refs")).rows[0].count),0);
    await client.query("BEGIN");
    await resolveRecipient(client,event);
    await resolveRecipient(client,event);
    await client.query("COMMIT");
    assert.equal(Number((await client.query("SELECT count(*) FROM notification_recipient_refs")).rows[0].count),2);
  } finally {
    await client.query("ROLLBACK");
    await client.query("SET search_path TO public");
    await client.query(`DROP SCHEMA ${schema} CASCADE`);
    client.release();
    await pool.end();
  }
});
