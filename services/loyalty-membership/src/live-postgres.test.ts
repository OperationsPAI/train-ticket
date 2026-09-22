import assert from "node:assert/strict";
import { test } from "node:test";
import { randomUUID } from "node:crypto";
import { Pool } from "pg";
import { startLoyaltyStorage, resolveOrderAccount } from "./bootstrap.js";
import { newCorrelationId, newEventId } from "@trainticket/ts-kit";

test("live payment replay credits the owner once with durable atomic ledger", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_LIVE_ORDER_ID,
}, async () => {
  const schema = `loyalty_replay_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, options: `-c search_path=${schema}`, max: 12 });
  const storage = await startLoyaltyStorage(pool);
  try {
    const orderId = process.env.TEST_LIVE_ORDER_ID!;
    const owner = await resolveOrderAccount(orderId);
    const response = await fetch(`${process.env.TEST_PAYMENT_BASE_URL}/api/v1/payment-intents/by-business/${orderId}`);
    assert.equal(response.status, 200);
    const payment = await response.json() as { status: string; payerRef: string; capturedAmount: { currency: string; minorUnits: number } };
    assert.equal(payment.status, "CAPTURED");
    assert.equal(payment.payerRef, owner);
    const sourceEventId = newEventId();
    const command = { orderId, ticketPrice: payment.capturedAmount, sourceEventId,
      confirmedAt: new Date(), correlationId: newCorrelationId() };
    const results = await Promise.all(Array.from({ length: 8 }, () => storage.runCommand((service) => service.accrueFromPaymentCaptured(command))));
    assert.equal(new Set(results.map(result => result.memberId)).size, 1);
    const member = await storage.runCommand(service => service.getMemberByAccountId(owner));
    assert.equal(member.ledger.length, 1);
    assert.equal(member.lifetimePoints, member.redeemablePoints);
    assert.ok(member.redeemablePoints > 0);
    const rows = await pool.query("SELECT count(*) AS count FROM points_ledger WHERE source_event_id=$1", [sourceEventId]);
    assert.equal(Number(rows.rows[0].count), 1);
    assert.equal((await pool.query("SELECT account_id FROM order_account_refs WHERE order_id=$1", [orderId])).rows[0].account_id, owner);
    const unique = Array.from({ length: 8 }, () => ({ ...command, sourceEventId: newEventId() }));
    await Promise.all(unique.map(item => storage.runCommand(service => service.accrueFromPaymentCaptured(item))));
    const accrued = await storage.runCommand(service => service.getMemberByAccountId(owner));
    assert.equal(accrued.ledger.length, 9);
    assert.equal(accrued.redeemablePoints, member.redeemablePoints * 9);
    const rollbackEvent = newEventId();
    await assert.rejects(storage.runCommand(async service => {
      await service.accrueFromPaymentCaptured({ ...command, sourceEventId: rollbackEvent });
      throw new Error("rollback verification");
    }), /rollback verification/);
    assert.equal(Number((await pool.query("SELECT count(*) AS count FROM points_ledger WHERE source_event_id=$1", [rollbackEvent])).rows[0].count), 0);
    const reloaded = await storage.repository.findByAccountId(owner);
    assert.equal(reloaded?.redeemablePoints, accrued.redeemablePoints);
  } finally {
    await storage.stop();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});
