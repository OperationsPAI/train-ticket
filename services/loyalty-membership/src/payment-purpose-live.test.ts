import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { Pool } from "pg";
import { startLoyaltyStorage,handlePaymentCaptured,handlePostSalesApplied } from "./bootstrap.js";
import type { EventEnvelope } from "./ports.js";

test("purchase events credit payer once while change payments and cases preserve points",{
  skip:!process.env.TEST_DATABASE_URL||!process.env.TEST_PURCHASE_PAYMENT_EVENT||!process.env.TEST_CHANGE_PAYMENT_EVENT||!process.env.TEST_APPLIED_CHANGE_EVENT,
},async()=>{
  const purchase=JSON.parse(await readFile(process.env.TEST_PURCHASE_PAYMENT_EVENT!,"utf8")) as EventEnvelope;
  const change=JSON.parse(await readFile(process.env.TEST_CHANGE_PAYMENT_EVENT!,"utf8")) as EventEnvelope;
  const applied=JSON.parse(await readFile(process.env.TEST_APPLIED_CHANGE_EVENT!,"utf8")) as EventEnvelope;
  assert.equal(purchase.payload.purpose,"purchase");assert.equal(change.payload.purpose,"change");assert.equal(applied.payload.caseType,"CHANGE");
  const schema=`loyalty_purpose_${randomUUID().replaceAll("-","")}`;
  const admin=new Pool({connectionString:process.env.TEST_DATABASE_URL});await admin.query(`CREATE SCHEMA ${schema}`);
  const pool=new Pool({connectionString:process.env.TEST_DATABASE_URL,options:`-c search_path=${schema}`});
  const storage=await startLoyaltyStorage(pool);
  try{
    await storage.runCommand(service=>handlePaymentCaptured(service,purchase));
    await storage.runCommand(service=>handlePaymentCaptured(service,purchase));
    const before=await storage.runCommand(service=>service.getMemberByAccountId(String(purchase.payload.payerRef)));
    assert.equal(before.ledger.length,1);
    await storage.runCommand(service=>handlePaymentCaptured(service,change));
    await storage.runCommand(service=>handlePostSalesApplied(service,applied));
    const after=await storage.runCommand(service=>service.getMemberByAccountId(String(purchase.payload.payerRef)));
    assert.deepEqual(after,before);
    assert.equal(Number((await pool.query("SELECT count(*) FROM points_ledger")).rows[0].count),1);
    assert.equal(Number((await pool.query("SELECT count(*) FROM order_account_refs WHERE order_id=$1",[change.payload.businessRef])).rows[0].count),0);
  }finally{await storage.stop();await admin.query(`DROP SCHEMA ${schema} CASCADE`);await admin.end()}
});
