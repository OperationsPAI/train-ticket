import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { Redis } from "ioredis";
import { Pool } from "pg";
import { RedisEventSubscriber, HandlerError, type EventHandler } from "./messaging.js";
import { ProcessedEventsGuard, withTransaction } from "./storage.js";

test("subscriber cache is bounded and evicted replays remain transactionally deduplicated", {
  skip: !process.env.TEST_REDIS_URL || !process.env.TEST_DATABASE_URL || !process.env.TEST_RECORDED_EVENTS,
}, async () => {
  const recorded = JSON.parse(await readFile(process.env.TEST_RECORDED_EVENTS!, "utf8")) as [string, string[]][];
  const envelopes = recorded.map(([, fields]) => fields[fields.indexOf("envelope") + 1]!);
  assert.ok(envelopes.length > RedisEventSubscriber.CONSUMED_EVENT_CACHE_LIMIT);
  const schema = `subscriber_retention_${randomUUID().replaceAll("-", "")}`;
  const stream = `events:${schema}`;
  const group = "retention-test";
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, options: `-c search_path=${schema}` });
  const redis = new Redis(process.env.TEST_REDIS_URL!);
  const subscriber = new RedisEventSubscriber(redis);
  const access = subscriber as unknown as {
    consumedEventIds: Set<string>;
    processEntry: (stream: string, group: string, consumer: string, entry: [string,string[]], handler: EventHandler) => Promise<void>;
  };
  let handled = 0;
  const handler: EventHandler = async envelope => {
    handled++;
    await withTransaction(pool, async client => {
      if (!await new ProcessedEventsGuard(client).tryStart(envelope.eventId, stream)) return;
      await client.query("INSERT INTO business_effects(event_id) VALUES ($1)", [envelope.eventId]);
    });
    return "ack";
  };
  try {
    await pool.query("CREATE TABLE processed_events(event_id text PRIMARY KEY, stream text, processed_at timestamptz DEFAULT now()); CREATE TABLE business_effects(event_id text PRIMARY KEY)");
    await redis.xgroup("CREATE", stream, group, "0", "MKSTREAM");
    for (let start=0; start<envelopes.length; start+=100) {
      const batch = redis.pipeline();
      for (const envelope of envelopes.slice(start,start+100)) batch.xadd(stream,"*","envelope",envelope);
      await batch.exec();
      const rows = await redis.xreadgroup("GROUP",group,"current","COUNT",100,"STREAMS",stream,">") as [string,[string,string[]][]][];
      for (const entry of rows[0]![1]) await access.processEntry(stream,group,"current",entry,handler);
      assert.ok(access.consumedEventIds.size <= RedisEventSubscriber.CONSUMED_EVENT_CACHE_LIMIT);
    }
    const first = JSON.parse(envelopes[0]!) as {eventId:string};
    assert.equal(access.consumedEventIds.has(first.eventId), false);
    await redis.xadd(stream,"*","envelope",envelopes[0]!);
    const replay = await redis.xreadgroup("GROUP",group,"current","COUNT",1,"STREAMS",stream,">") as [string,[string,string[]][]][];
    await access.processEntry(stream,group,"current",replay[0]![1][0]!,handler);
    assert.equal(handled,envelopes.length+1);
    assert.equal(Number((await pool.query("SELECT count(*) FROM business_effects")).rows[0].count),envelopes.length);
    assert.equal(Number((await pool.query("SELECT count(*) FROM processed_events")).rows[0].count),envelopes.length);
    assert.equal((await redis.xpending(stream,group) as [number,...unknown[]])[0],0);
    await redis.xadd(stream,"*","envelope",envelopes[0]!);
    const delayed = await redis.xreadgroup("GROUP",group,"previous","COUNT",1,"STREAMS",stream,">") as [string,[string,string[]][]][];
    const delayedId = delayed[0]![1][0]![0];
    await redis.xclaim(stream,group,"previous",0,delayedId,"IDLE",61_000,"RETRYCOUNT",20);
    access.consumedEventIds.delete(first.eventId);
    const recover = subscriber as unknown as { claimAndProcess:(stream:string,group:string,consumer:string,handler:EventHandler)=>Promise<void> };
    await recover.claimAndProcess(stream,group,"current",handler);
    assert.equal((await redis.xpending(stream,group) as [number,...unknown[]])[0],0);
    assert.equal(await redis.xlen(`${stream}:dlq`),0);
    assert.equal(Number((await pool.query("SELECT count(*) FROM business_effects")).rows[0].count),envelopes.length);
    assert.equal(handled,envelopes.length+2);
    access.consumedEventIds.delete(first.eventId);
    await redis.xadd(stream,"*","envelope",envelopes[0]!);
    const failing = await redis.xreadgroup("GROUP",group,"current","COUNT",1,"STREAMS",stream,">") as [string,[string,string[]][]][];
    const databaseFailure:EventHandler = async () => {
      try { await pool.query("SELECT 1 / 0"); }
      catch(error) { throw new HandlerError("transient", error instanceof Error ? error.message : String(error)); }
      return "ack";
    };
    for(let attempt=1;attempt<=5;attempt++) {
      await access.processEntry(stream,group,"current",failing[0]![1][0]!,databaseFailure);
      assert.equal(await redis.xlen(`${stream}:dlq`),attempt===5?1:0);
    }
    assert.equal((await redis.xpending(stream,group) as [number,...unknown[]])[0],0);
  } finally {
    await redis.del(stream);
    await redis.del(`${stream}:dlq`);
    await subscriber.close();
    await pool.end();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});
