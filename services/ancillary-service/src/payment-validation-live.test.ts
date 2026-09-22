import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {test} from "node:test";
import {HttpAncillaryPayments} from "./payments.js";

test("primary ticket payment cannot confirm an ancillary item",{skip:!process.env.TEST_ANCILLARY_VOID_SAMPLES || !process.env.TEST_PRIMARY_PAYMENT},async()=>{
  const sample=JSON.parse(await readFile(process.env.TEST_ANCILLARY_VOID_SAMPLES!,"utf8"));
  const gateway=new HttpAncillaryPayments(process.env.PAYMENT_URL,process.env.JOURNEY_ORDER_URL);
  await assert.rejects(gateway.verify(sample.target,sample.void.correlationId),/requires its own payment/);
  await assert.rejects(gateway.verify({...sample.target,paymentIntentId:process.env.TEST_PRIMARY_PAYMENT},sample.void.correlationId),/does not match/);
});
