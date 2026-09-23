import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { setTimeout } from "node:timers/promises";
import { uuidV7 } from "@trainticket/ts-kit";
import { HttpJourneyOrderClient } from "../src/application.js";
import { WaitlistEntry } from "../src/domain.js";
import { HttpFarePricingClient, HttpOfferManagementClient } from "../src/promotion.js";

test("waitlist clients create an order using the current deployed response fields", {
  skip: !process.env.TEST_CLIENT_BASE || !process.env.TEST_OFFER_BINDING_SAMPLE || !process.env.TEST_OFFER_ITINERARY,
}, async () => {
  const sample = JSON.parse(await readFile(process.env.TEST_OFFER_BINDING_SAMPLE!, "utf8"));
  const itinerary = JSON.parse(await readFile(process.env.TEST_OFFER_ITINERARY!, "utf8"));
  const base = (service: string) => `${process.env.TEST_CLIENT_BASE}/${service}:8080/proxy`;
  const entry = WaitlistEntry.create({
    accountId: sample.request.accountId, travelerRefs: sample.request.travelerRefs,
    itineraryRef: sample.request.itineraryRef, segmentRef: sample.itinerary.segmentRefs[0],
    departureDate: itinerary.legs[0].departureTime.slice(0, 10), seatClass: "SECOND_CLASS",
    priority: { tripCount: 0 }, createdAt: new Date(),
  });
  const fare = await new HttpFarePricingClient(base("fare-pricing"), sample.request.channelId).quote(entry);
  assert.match(fare.fareQuoteId, /^fq-/);
  const offers = new HttpOfferManagementClient(base("offer-management"), sample.request.channelId);
  let offer: Awaited<ReturnType<typeof offers.createOffer>> | undefined;
  for (let attempt = 0; attempt < 30; attempt++) {
    try {
      offer = await offers.createOffer(entry, fare.fareQuoteId);
      break;
    } catch (error) {
      if (!(error instanceof Error) || !error.message.includes("failed with status 422") || attempt === 29) throw error;
      await setTimeout(200);
    }
  }
  assert.ok(offer);
  const now = new Date();
  entry.offer(offer.offerId, offer.offerVersion, fare.fareQuoteId, undefined, now, new Date(now.getTime() + 900_000));
  const order = await new HttpJourneyOrderClient(base("journey-order")).createOrder(entry);
  assert.match(order.orderId, /^ord-/);
  console.log(JSON.stringify({ fare: fare.fareQuoteId, offer: offer.offerId, order: order.orderId }));
  try {
    const response = await fetch(`${base("journey-order")}/api/v1/journey-orders/${order.orderId}`);
    assert.equal(response.status, 200);
    const actual = await response.json() as { orderId: string };
    assert.equal(actual.orderId, order.orderId);
    assert.deepEqual(await new HttpJourneyOrderClient(base("journey-order")).createOrder(entry), order);
  } finally {
    const cancelled = await fetch(`${base("journey-order")}/api/v1/journey-orders/${order.orderId}/cancel`, {
      method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": uuidV7() },
      body: JSON.stringify({ reason: "USER_CANCELLED" }),
    });
    assert.equal(cancelled.status, 200, await cancelled.text());
  }
});
