import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import { Pool } from "pg";
import { OutboxAppender, withTransaction, type EventEnvelope } from "@trainticket/ts-kit";
import { AncillaryApplicationService } from "./application.js";
import { HttpAncillaryReferences } from "./references.js";
import { PostgresAncillaryRepository } from "./adapters/storage/repository.js";
import { migrationsDirectory } from "./adapters/storage/runtime.js";

test("issued ticket purchase preserves real references and rejects invalid draft atomically", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_ANCILLARY_TICKET,
}, async () => {
  const sample = JSON.parse(await readFile(process.env.TEST_ANCILLARY_TICKET!,"utf8"));
  const schema = "ancillary_refs_"+randomUUID().replaceAll("-","");
  const admin = new Pool({connectionString:process.env.TEST_DATABASE_URL,max:1});
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({connectionString:process.env.TEST_DATABASE_URL,options:`-c search_path=${schema}`,max:3});
  try {
    for (const file of (await readdir(migrationsDirectory())).filter(file=>file.endsWith(".sql")).sort()) await pool.query(await readFile(`${migrationsDirectory()}/${file}`,"utf8"));
    const gateway = new HttpAncillaryReferences(process.env.JOURNEY_ORDER_URL,process.env.ENTITLEMENT_TICKETING_URL);
    const run = <T>(operation:(service:AncillaryApplicationService)=>Promise<T>) => withTransaction(pool, async client => operation(new AncillaryApplicationService(new PostgresAncillaryRepository(client),{publish:async (event:EventEnvelope)=>{await new OutboxAppender(client).append(event);}},undefined,gateway)));
    const now = new Date();
    const catalog = await run(service=>service.createCatalogItem({serviceType:"MEAL",displayName:"Ticket referenced meal",attachmentScope:"SEGMENT",modalities:["TRAIN"],price:{currency:"CNY",minorUnits:1200},salesWindow:{startAt:now.toISOString(),endAt:sample.departure},purchaseCutoffHoursBeforeDeparture:1,eligibilityRuleVersion:"min-v1",requiresEntitlementRef:true,requiresSegmentRef:true,fulfillmentMethod:"VOUCHER"}));
    const correlation = "corr-"+sample.ticket.entitlementId.slice(4);
    await run(service=>service.publishCatalogItem(catalog.catalogItemId,{approvalRef:"loadgen",expectedVersion:catalog.version},correlation));
    const input = {catalogItemId:catalog.catalogItemId,journeyOrderId:sample.order.orderId,travelerRef:sample.ticket.travelerRef,segmentRef:sample.ticket.segmentRef,entitlementRef:sample.ticket.entitlementId,departureAt:sample.departure,quantity:1};
    await assert.rejects(run(service=>service.draftOffer({...input,departureAt:now.toISOString()})),/Departure time/);
    assert.equal((await pool.query("SELECT count(*) FROM ancillary_offers")).rows[0].count,"0");
    const draft = await run(service=>service.draftOffer(input));
    const quoted = await run(service=>service.quoteOffer(draft.ancillaryOfferId,{expectedVersion:draft.offerVersion},correlation));
    const selected = await run(service=>service.selectOffer(draft.ancillaryOfferId,{journeyOrderId:sample.order.orderId,expectedVersion:quoted.offerVersion},correlation));
    await run(service=>service.confirmOrderItem(selected.ancillaryOrderItemId,{},correlation));
    const confirmed = await run(service=>service.confirmOrderItem(selected.ancillaryOrderItemId,{},correlation));
    assert.equal(confirmed.entitlementRef,sample.ticket.entitlementId);
    assert.equal(confirmed.status,"CONFIRMED");
    const restored = await new PostgresAncillaryRepository(pool).getOrderItem(selected.ancillaryOrderItemId);
    assert.deepEqual(restored,JSON.parse(JSON.stringify(confirmed)));
  } finally {await pool.end();await admin.query(`DROP SCHEMA ${schema} CASCADE`);await admin.end();}
});
