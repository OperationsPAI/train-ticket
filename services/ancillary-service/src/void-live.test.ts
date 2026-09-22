import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import { Pool } from "pg";
import { OutboxAppender,withTransaction,type EventEnvelope } from "@trainticket/ts-kit";
import { AncillaryApplicationService } from "./application.js";
import { AncillaryOrderItem } from "./domain.js";
import { PostgresAncillaryRepository } from "./adapters/storage/repository.js";
import { migrationsDirectory } from "./adapters/storage/runtime.js";

test("ticket void cancels only its ancillary items and prevents stale confirmation after restart",{skip:!process.env.TEST_DATABASE_URL || !process.env.TEST_ANCILLARY_VOID_SAMPLES},async()=>{
  const source=JSON.parse(await readFile(process.env.TEST_ANCILLARY_VOID_SAMPLES!,"utf8"));
  const schema="ancillary_void_"+randomUUID().replaceAll("-","");
  const admin=new Pool({connectionString:process.env.TEST_DATABASE_URL,max:1});await admin.query(`CREATE SCHEMA ${schema}`);
  const pool=new Pool({connectionString:process.env.TEST_DATABASE_URL,options:`-c search_path=${schema}`,max:4});
  try {
    for(const file of (await readdir(migrationsDirectory())).filter(file=>file.endsWith(".sql")).sort())await pool.query(await readFile(`${migrationsDirectory()}/${file}`,"utf8"));
    for(const item of [source.target,source.unrelated])await pool.query("INSERT INTO ancillary_order_items(id,version,data) VALUES($1,$2,$3)",[item.ancillaryOrderItemId,item.aggregateVersion,item]);
    const run=<T>(operation:(service:AncillaryApplicationService)=>Promise<T>)=>withTransaction(pool,async client=>operation(new AncillaryApplicationService(new PostgresAncillaryRepository(client),{publish:async(event:EventEnvelope)=>{await new OutboxAppender(client).append(event);}})));
    await pool.query("ALTER TABLE outbox ADD CONSTRAINT reject_cancel CHECK(envelope->>'eventType'<>'AncillaryOrderItemCancelled')");
    await assert.rejects(run(service=>service.handleJourneyOrderCancelled(source.void)));
    assert.equal((await pool.query("SELECT count(*) FROM ancillary_voided_tickets")).rows[0].count,"0");
    await pool.query("ALTER TABLE outbox DROP CONSTRAINT reject_cancel");
    await run(service=>service.handleJourneyOrderCancelled(source.void));
    await run(service=>service.handleJourneyOrderCancelled(source.void));
    const repository=new PostgresAncillaryRepository(pool);
    assert.equal((await repository.getOrderItem(source.target.ancillaryOrderItemId))?.status,"CANCELLED");
    assert.equal((await repository.getOrderItem(source.unrelated.ancillaryOrderItemId))?.status,"CONFIRMED");
    const stale=AncillaryOrderItem.fromSnapshot(source.target);stale.fulfillmentReady({});
    await assert.rejects(withTransaction(pool,client=>new PostgresAncillaryRepository(client).saveOrderItem(stale.toSnapshot())),/VOIDED/);
    assert.equal((await pool.query("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='AncillaryOrderItemCancelled'")).rows[0].count,"1");
  }finally{await pool.end();await admin.query(`DROP SCHEMA ${schema} CASCADE`);await admin.end();}
});
