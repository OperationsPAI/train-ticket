import assert from "node:assert/strict";
import { test } from "node:test";
import { randomUUID } from "node:crypto";
import { Pool } from "pg";
import { startLoyaltyStorage } from "./bootstrap.js";
import { resolveCapturedPaymentFacts } from "./payment-facts.js";

test("targeted payment reconciliation preserves original facts and credits once", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_RECONCILE_PAYMENT_ID,
}, async () => {
  const paymentIntentId = process.env.TEST_RECONCILE_PAYMENT_ID!;
  const facts = await resolveCapturedPaymentFacts(paymentIntentId);
  assert.equal(facts.events.length, 1);
  const schema = `loyalty_reconcile_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, options: `-c search_path=${schema}`, max: 12 });
  const storage = await startLoyaltyStorage(pool);
  try {
    const reconciled = await Promise.all(Array.from({ length: 8 }, () =>
      storage.runCommand(service => service.reconcilePayment(paymentIntentId))));
    for (const result of reconciled) {
      assert.equal(result.member.accountId, facts.payerRef);
      assert.deepEqual(result.sourceEventIds, [facts.events[0].eventId]);
    }
    const entries = await pool.query("SELECT points,source_event_id,occurred_at FROM points_ledger");
    assert.equal(entries.rowCount, 1);
    assert.equal(entries.rows[0].source_event_id, facts.events[0].eventId);
    assert.equal(entries.rows[0].occurred_at.getTime(), new Date(facts.events[0].occurredAt).getTime());
    assert.equal(entries.rows[0].points, Math.floor(facts.capturedAmount.minorUnits / 100));
    const snapshot = await storage.runCommand(service => service.getMemberByAccountId(facts.payerRef));
    assert.equal(snapshot.redeemablePoints, entries.rows[0].points);
    assert.equal(snapshot.ledger.length, 1);
  } finally {
    await storage.stop();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});
