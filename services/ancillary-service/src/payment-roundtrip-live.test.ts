import assert from "node:assert/strict";
import {readFile,readdir,writeFile} from "node:fs/promises";
import {randomUUID} from "node:crypto";
import {test} from "node:test";
import {Pool} from "pg";
import {OutboxAppender,withTransaction,uuidV7,type EventEnvelope} from "@trainticket/ts-kit";
import {AncillaryApplicationService} from "./application.js";
import {HttpAncillaryReferences} from "./references.js";
import {HttpAncillaryPayments} from "./payments.js";
import {PostgresAncillaryRepository} from "./adapters/storage/repository.js";
import {migrationsDirectory} from "./adapters/storage/runtime.js";

test("ancillary uses its own real payment and settled refund",{skip:!process.env.TEST_ANCILLARY_PAID_TICKET || !process.env.TEST_DATABASE_URL},async()=>{
  const source=JSON.parse(await readFile(process.env.TEST_ANCILLARY_PAID_TICKET!,"utf8"));
  const base=process.env.PAYMENT_URL!;
  const correlation=`corr-${uuidV7()}`;
  async function request(path:string,body?:unknown){
    const response=await fetch(base+path,{method:body?"POST":"GET",body:body?JSON.stringify(body):undefined,headers:{"content-type":"application/json","idempotency-key":uuidV7(),"x-correlation-id":correlation},signal:AbortSignal.timeout(12000)});
    assert.ok(response.ok,`${response.status} ${await response.clone().text()}`);return response.json() as Promise<any>;
  }
  const schema="ancillary_paid_"+randomUUID().replaceAll("-","");
  const admin=new Pool({connectionString:process.env.TEST_DATABASE_URL,max:1});await admin.query(`CREATE SCHEMA ${schema}`);
  const pool=new Pool({connectionString:process.env.TEST_DATABASE_URL,options:`-c search_path=${schema}`,max:4});
  try{
    for(const name of (await readdir(migrationsDirectory())).filter(name=>name.endsWith(".sql")).sort())await pool.query(await readFile(`${migrationsDirectory()}/${name}`,"utf8"));
    const gateway=new HttpAncillaryReferences(process.env.JOURNEY_ORDER_URL,process.env.ENTITLEMENT_TICKETING_URL);
    const payments=new HttpAncillaryPayments(base,process.env.JOURNEY_ORDER_URL);
    const run=<T>(operation:(service:AncillaryApplicationService)=>Promise<T>)=>withTransaction(pool,async client=>operation(new AncillaryApplicationService(new PostgresAncillaryRepository(client),{publish:async(event:EventEnvelope)=>{await new OutboxAppender(client).append(event);}},undefined,gateway,payments)));
    const catalog=await run(service=>service.createCatalogItem({serviceType:"MEAL",displayName:"Paid ticket meal",attachmentScope:"SEGMENT",modalities:["TRAIN"],price:{currency:"CNY",minorUnits:1200},salesWindow:{startAt:new Date().toISOString(),endAt:source.departure},purchaseCutoffHoursBeforeDeparture:1,eligibilityRuleVersion:"min-v1",requiresEntitlementRef:true,requiresSegmentRef:true,fulfillmentMethod:"VOUCHER"}));
    await run(service=>service.publishCatalogItem(catalog.catalogItemId,{approvalRef:"loadgen",expectedVersion:1},correlation));
    const draft=await run(service=>service.draftOffer({catalogItemId:catalog.catalogItemId,journeyOrderId:source.order.orderId,travelerRef:source.ticket.travelerRef,segmentRef:source.ticket.segmentRef,entitlementRef:source.ticket.entitlementId,departureAt:source.departure,quantity:1}));
    const quote=await run(service=>service.quoteOffer(draft.ancillaryOfferId,{expectedVersion:1},correlation));
    const selected=await run(service=>service.selectOffer(draft.ancillaryOfferId,{journeyOrderId:source.order.orderId,expectedVersion:quote.offerVersion},correlation));
    await assert.rejects(run(service=>service.confirmOrderItem(selected.ancillaryOrderItemId,{},correlation)),/requires its own payment/);
    const prepared=await run(service=>service.preparePayment(selected.ancillaryOrderItemId,correlation));
    const replay=await run(service=>service.preparePayment(selected.ancillaryOrderItemId,correlation));
    assert.equal(replay.paymentIntentId,prepared.paymentIntentId);
    await assert.rejects(run(service=>service.confirmOrderItem(selected.ancillaryOrderItemId,{},correlation)),/not fully captured/);
    await request(`/api/v1/payment-intents/${prepared.paymentIntentId}/capture`,{channelRef:{channel:"ALIPAY_SIM"}});
    let payment:any;
    for(let attempt=0;attempt<40;attempt++){payment=await request(`/api/v1/payment-intents/${prepared.paymentIntentId}`);if(payment.status==="CAPTURED")break;await new Promise(resolve=>setTimeout(resolve,250));}
    assert.equal(payment.status,"CAPTURED");assert.equal(payment.businessRef,selected.ancillaryOrderItemId);assert.equal(payment.payerRef,source.order.accountId);assert.equal(payment.purpose,"ancillary");
    const facts=await request(`/api/v1/internal/payment-intents/${prepared.paymentIntentId}/captured-facts`);
    const capture=facts.events.find((event:EventEnvelope)=>event.eventType==="PaymentCaptured");assert.ok(capture);
    await run(service=>service.handleJourneyOrderCancelled(capture));
    await run(service=>service.handleJourneyOrderCancelled(capture));
    const confirmed=await new PostgresAncillaryRepository(pool).getOrderItem(selected.ancillaryOrderItemId);assert.ok(confirmed);
    assert.equal(confirmed.status,"CONFIRMED");
    assert.equal((await run(service=>service.confirmOrderItem(selected.ancillaryOrderItemId,{},correlation))).aggregateVersion,confirmed.aggregateVersion);
    const cancelled=await run(service=>service.cancelOrderItem(selected.ancillaryOrderItemId,{reasonCode:"USER_CANCELLED",source:"USER"},correlation));
    assert.equal(cancelled.status,"REFUND_PENDING");
    const pending=(await pool.query("SELECT envelope FROM outbox WHERE envelope->>'eventType'='AncillaryOrderItemRefundPending'")).rows[0].envelope;
    assert.equal(pending.payload.paymentIntentId,prepared.paymentIntentId);
    const refund=await request("/api/v1/refunds",{paymentIntentId:prepared.paymentIntentId,amount:prepared.payableAmount,reason:"USER_CANCELLED",businessCaseRef:`ancillary:${prepared.ancillaryOrderItemId}`,channelRef:payment.channelRef});
    let settled:any;
    for(let attempt=0;attempt<60;attempt++){settled=await request(`/api/v1/refunds/${refund.refundId}`);if(settled.status==="SETTLED")break;await new Promise(resolve=>setTimeout(resolve,250));}
    assert.equal(settled.status,"SETTLED");
    await run(service=>service.reconcileRefund(selected.ancillaryOrderItemId,correlation));
    const final=await new PostgresAncillaryRepository(pool).getOrderItem(selected.ancillaryOrderItemId);assert.ok(final);
    assert.equal(final.status,"REFUNDED");
    payment=await request(`/api/v1/payment-intents/${prepared.paymentIntentId}`);assert.deepEqual(payment.refundedAmount,payment.capturedAmount);
    if(process.env.TEST_ANCILLARY_PAYMENT_EVIDENCE)await writeFile(process.env.TEST_ANCILLARY_PAYMENT_EVIDENCE,JSON.stringify({item:final,payment,refund:settled,refundEvent:pending}));
  }finally{await pool.end();await admin.query(`DROP SCHEMA ${schema} CASCADE`);await admin.end();}
});
