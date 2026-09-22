import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { uuidV7 } from "@trainticket/ts-kit";
import { HttpOfferManagementClient } from "../src/promotion.js";
import { WaitlistEntry } from "../src/domain.js";

test("waitlist offer client binds its actual fare quote", { skip: !process.env.TEST_OFFER_BINDING_SAMPLE || !process.env.TEST_OFFER_URL }, async () => {
  const sample = JSON.parse(await readFile(process.env.TEST_OFFER_BINDING_SAMPLE!, "utf8"));
  const itinerary = JSON.parse(await readFile(process.env.TEST_OFFER_ITINERARY!, "utf8"));
  const entry = WaitlistEntry.create({ entryId: `wl-${uuidV7()}`, accountId: sample.request.accountId,
    travelerRefs: sample.request.travelerRefs, itineraryRef: sample.request.itineraryRef,
    segmentRef: sample.itinerary.segmentRefs[0], departureDate: itinerary.legs[0].departureTime.slice(0, 10), seatClass: "SECOND_CLASS",
    priority: { tripCount: 0 }, createdAt: new Date() });
  const client = new HttpOfferManagementClient(process.env.TEST_OFFER_URL!, sample.request.channelId);
  const fareQuoteRef = sample.request.fareQuoteRef;
  const offer = await client.createOffer(entry, fareQuoteRef);
  const response = await fetch(`${process.env.TEST_OFFER_URL}/api/v1/offers/${offer.offerId}`);
  assert.equal(response.status, 200);
  const detail = await response.json() as Record<string, unknown>;
  assert.equal(detail.fareQuoteRef, fareQuoteRef);
});
