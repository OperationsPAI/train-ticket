import assert from "node:assert/strict";
import { test } from "node:test";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { Pool } from "pg";
import { Redis } from "ioredis";
import { RedisStreamEventSubscriber, type EventEnvelope } from "@trainticket/ts-kit";
import { startLoyaltyStorage, handlePaymentCaptured } from "./bootstrap.js";

test("an account lock does not delay a distinct payer in the same event batch", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_REDIS_URL || !process.env.TEST_PAYMENT_EVENTS,
}, async () => {
  const source = JSON.parse(await readFile(process.env.TEST_PAYMENT_EVENTS!,"utf8")) as [string,string[]][];
  const payments = source.map(([,fields]) => JSON.parse(fields[fields.indexOf("envelope")+1]) as EventEnvelope)
    .filter(event=>event.eventType==="PaymentCaptured" && event.payload.purpose==="purchase");
  const first=payments[0];
  const second=payments.find(event=>event.payload.payerRef!==first.payload.payerRef)!;
  assert.ok(second);
  const schema=`loyalty_parallel_${randomUUID().replaceAll("-","")}`;
  const stream=`test:${schema}`, group="loyalty-parallel";
  const admin=new Pool({connectionString:process.env.TEST_DATABASE_URL});await admin.query(`CREATE SCHEMA ${schema}`);
  const pool=new Pool({connectionString:process.env.TEST_DATABASE_URL,options:`-c search_path=${schema}`});
  const storage=await startLoyaltyStorage(pool);
  const redis=new Redis(process.env.TEST_REDIS_URL!);
  const subscriber=new RedisStreamEventSubscriber(redis);
  const blocker=await pool.connect();
  const previous=process.env.CONSUMER_BATCH_CONCURRENCY;
  process.env.CONSUMER_BATCH_CONCURRENCY="4";
  const awaitCondition=async(condition:()=>Promise<boolean>)=>{
    const end=Date.now()+5000;
    while(Date.now()<end){if(await condition())return;await new Promise(resolve=>setTimeout(resolve,10))}
    throw new Error("independent payment did not complete");
  };
  try{
    await redis.xgroup("CREATE",stream,group,"0","MKSTREAM");
    await blocker.query("BEGIN");
    await blocker.query("SELECT pg_advisory_xact_lock(hashtextextended($1,0))",[`loyalty-account:${first.payload.payerRef}`]);
    await redis.xadd(stream,"*","envelope",JSON.stringify(first));
    await redis.xadd(stream,"*","envelope",JSON.stringify(second));
    await subscriber.subscribe([stream],group,"parallel",async event=>{
      await storage.runCommand(service=>handlePaymentCaptured(service,event));
      return "ack";
    });
    await awaitCondition(async()=>Number((await pool.query("SELECT count(*) FROM points_ledger WHERE source_event_id=$1",[second.eventId])).rows[0].count)===1);
    assert.equal(Number((await pool.query("SELECT count(*) FROM points_ledger WHERE source_event_id=$1",[first.eventId])).rows[0].count),0);
    await blocker.query("COMMIT");
    await awaitCondition(async()=>Number((await pool.query("SELECT count(*) FROM points_ledger")).rows[0].count)===2);
    await awaitCondition(async()=>(await redis.xpending(stream,group) as [number,...unknown[]])[0]===0);
    await redis.xadd(stream,"*","envelope",JSON.stringify(first));
    await new Promise(resolve=>setTimeout(resolve,200));
    assert.equal(Number((await pool.query("SELECT count(*) FROM points_ledger")).rows[0].count),2);
  } finally {
    await blocker.query("ROLLBACK");blocker.release();
    await redis.del(stream);await subscriber.close();
    await storage.stop();await admin.query(`DROP SCHEMA ${schema} CASCADE`);await admin.end();
    if(previous===undefined)delete process.env.CONSUMER_BATCH_CONCURRENCY;else process.env.CONSUMER_BATCH_CONCURRENCY=previous;
  }
});
