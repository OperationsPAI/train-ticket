import assert from "node:assert/strict";
import {readFile,readdir} from "node:fs/promises";
import {randomUUID} from "node:crypto";
import {test} from "node:test";
import {Pool} from "pg";
import {OutboxAppender,withTransaction,uuidV7,type EventEnvelope} from "@trainticket/ts-kit";
import {AncillaryApplicationService} from "./application.js";
import {HttpAncillaryPayments} from "./payments.js";
import {PostgresAncillaryRepository} from "./adapters/storage/repository.js";
import {migrationsDirectory} from "./adapters/storage/runtime.js";

test("recorded late ancillary collection prepares sequential versions",{skip:!process.env.TEST_LATE_ANCILLARY_ITEM},async()=>{
  const item=JSON.parse(await readFile(process.env.TEST_LATE_ANCILLARY_ITEM!,"utf8"));
  const schema="late_ancillary_"+randomUUID().replaceAll("-","");
  const admin=new Pool({connectionString:process.env.TEST_DATABASE_URL,max:1});
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool=new Pool({connectionString:process.env.TEST_DATABASE_URL,options:`-c search_path=${schema}`,max:4});
  const correlation=`corr-${uuidV7()}`;
  try{
    for(const name of (await readdir(migrationsDirectory())).filter(name=>name.endsWith(".sql")).sort())await pool.query(await readFile(`${migrationsDirectory()}/${name}`,"utf8"));
    await pool.query("INSERT INTO ancillary_order_items(id,version,data) VALUES ($1,$2,$3)",[item.ancillaryOrderItemId,item.aggregateVersion,item]);
    const payments=new HttpAncillaryPayments(process.env.PAYMENT_URL,process.env.JOURNEY_ORDER_URL);
    const run=<T>(operation:(service:AncillaryApplicationService)=>Promise<T>)=>withTransaction(pool,async client=>operation(new AncillaryApplicationService(new PostgresAncillaryRepository(client),{publish:async(event:EventEnvelope)=>{await new OutboxAppender(client).append(event);}},undefined,undefined,payments)));
    const prepared=await run(service=>service.prepareLatePaymentResolution(item.ancillaryOrderItemId,correlation));
    assert.equal(prepared.status,"REFUND_PENDING");
    assert.equal(prepared.aggregateVersion,item.aggregateVersion+2);
    assert.equal((await run(service=>service.prepareLatePaymentResolution(item.ancillaryOrderItemId,correlation))).aggregateVersion,prepared.aggregateVersion);
    assert.equal((await pool.query("SELECT count(*)::int AS n FROM outbox WHERE envelope->>'eventType'='AncillaryOrderItemCancelled'")).rows[0].n,1);
  }finally{await pool.end();await admin.query(`DROP SCHEMA ${schema} CASCADE`);await admin.end();}
});
