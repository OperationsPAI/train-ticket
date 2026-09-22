import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { HttpAncillaryReferences, ReferenceUnavailable } from "./references.js";

test("ancillary references match real ticket, order and departure", { skip: !process.env.TEST_ANCILLARY_TICKET }, async () => {
  const sample = JSON.parse(await readFile(process.env.TEST_ANCILLARY_TICKET!, "utf8"));
  const gateway = new HttpAncillaryReferences(process.env.JOURNEY_ORDER_URL, process.env.ENTITLEMENT_TICKETING_URL);
  const input = { journeyOrderId: sample.order.orderId, travelerRef: sample.ticket.travelerRef,
    entitlementRef: sample.ticket.entitlementId, segmentRef: sample.ticket.segmentRef, departureAt: sample.departure };
  assert.equal((await gateway.verify(input,true)).departureAt, new Date(sample.departure).toISOString());
  await assert.rejects(gateway.verify({ ...input, departureAt: new Date(Date.parse(sample.departure) + 3600000).toISOString() },true), /Departure time/);
  await assert.rejects(gateway.verify({ ...input, entitlementRef: undefined },true), /issued entitlement/);
  await assert.rejects(gateway.verify({ ...input, entitlementRef: sample.order.orderId.replace("ord-","ent-") },true), /does not exist|does not match/);
  const unavailable = new HttpAncillaryReferences("http://127.0.0.1:1",process.env.ENTITLEMENT_TICKETING_URL);
  await assert.rejects(unavailable.verify(input,true),ReferenceUnavailable);
});
