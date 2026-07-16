import assert from "node:assert/strict";
import test from "node:test";
import { InMemoryEventPublisher, isUuidV7 } from "@trainticket/ts-kit";
import { InMemoryWaitlistRepository, WaitlistApplicationService } from "../src/application.js";
import type { CapacityAvailabilityClient, FarePricingClient, JourneyOrderClient, OfferManagementClient } from "../src/promotion.js";
import type { WaitlistEntry } from "../src/domain.js";
import { deterministicEventId } from "../src/publisher.js";

class StubFarePricing implements FarePricingClient {
  async quote(entry: WaitlistEntry) { return { fareQuoteId: `fq-${entry.entryId}` }; }
}

class StubOfferManagement implements OfferManagementClient {
  async createOffer(entry: WaitlistEntry) { return { offerId: `off-${entry.entryId}`, offerVersion: 1 }; }
}

class StubCapacity implements CapacityAvailabilityClient {
  async hold(entry: WaitlistEntry) { return { capacityHoldId: `hold-${entry.entryId}` }; }
  async releaseHold() {}
}

class StubJourneyOrder implements JourneyOrderClient {
  async createOrder(entry: WaitlistEntry) { return { orderId: `ord-${entry.entryId}`, seatAssignment: null }; }
}

test("deterministic event ids are stable UUIDv7-shaped event ids", () => {
  const seed = "waitlist:WaitlistQueued:wlr-123:2";
  const eventId = deterministicEventId(seed);

  assert.equal(eventId, deterministicEventId(seed));
  assert.match(eventId, /^evt-/u);
  assert.equal(isUuidV7(eventId.slice("evt-".length)), true);
  assert.notEqual(eventId, seed);
});

test("join publishes waitlist contract facts with deterministic ids and fields", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, undefined, undefined, undefined, () => new Date("2026-01-01T00:00:00.000Z"));

  const resource = await service.join({
    accountId: "acc-1",
    travelerRef: "tvl-1",
    segmentRef: "seg-1",
    travelClass: "SECOND",
    deadline: "2026-07-20T00:00:00.000Z",
    paymentGuaranteeRef: "pay-auth-1",
    itineraryRef: "itn-1",
    intentFingerprint: "intent-1",
  }, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c123");

  assert.deepEqual(publisher.envelopes.map((event) => event.eventType), ["WaitlistRequestCreated", "WaitlistPaymentAuthorizationRequested", "WaitlistQueued"]);
  assert.deepEqual(publisher.envelopes.map((event) => event.eventId), [
    deterministicEventId(`waitlist:WaitlistRequestCreated:${resource.waitlistRequestId}:1`),
    deterministicEventId(`waitlist:WaitlistPaymentAuthorizationRequested:${resource.waitlistRequestId}:1`),
    deterministicEventId(`waitlist:WaitlistQueued:${resource.waitlistRequestId}:1`),
  ]);
  assert.equal(publisher.envelopes.every((event) => isUuidV7(event.eventId.slice("evt-".length))), true);

  const created = publisher.envelopes[0]?.payload as Record<string, unknown>;
  assert.equal(created.waitlistRequestId, resource.waitlistRequestId);
  assert.equal(created.accountId, "acc-1");
  assert.equal(created.travelerRef, "tvl-1");
  assert.equal(created.segmentRef, "seg-1");
  assert.equal(created.travelClass, "SECOND");
  assert.equal(created.deadline, "2026-07-20T00:00:00.000Z");
  assert.equal(created.paymentGuaranteeRef, "pay-auth-1");
  assert.equal(created.itineraryRef, "itn-1");
  assert.equal(created.intentFingerprint, "intent-1");
  assert.equal(created.status, "DRAFT");
  assert.equal(typeof created.createdAt, "string");

  const payment = publisher.envelopes[1]?.payload as Record<string, unknown>;
  assert.equal(payment.paymentGuaranteeRef, "pay-auth-1");
  assert.equal(payment.requestedAt, created.createdAt);
  assert.equal(payment.status, "DRAFT");

  const queued = publisher.envelopes[2]?.payload as Record<string, unknown>;
  assert.equal(queued.status, "QUEUED");
  assert.equal(queued.itineraryRef, "itn-1");
  assert.equal(queued.intentFingerprint, "intent-1");
  assert.equal(queued.queuedAt, created.createdAt);
});

test("cancel publishes WaitlistCancelled with supplied reason without defaulting API input", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, undefined, undefined, undefined, () => new Date("2026-01-01T00:00:00.000Z"));
  const resource = await service.join({ accountId: "acc", travelerRef: "tvl", segmentRef: "seg", departureDate: "2026-07-20", travelClass: "SECOND", paymentGuaranteeRef: "pay-auth-1", itineraryRef: "itn", intentFingerprint: "intent" });

  await service.cancel(resource.waitlistRequestId, { reason: "USER_REQUESTED" });

  const event = publisher.findByEventType("WaitlistCancelled")[0];
  assert.equal(event?.eventId, deterministicEventId(`waitlist:WaitlistCancelled:${resource.waitlistRequestId}:2`));
  assert.equal(event?.payload.reason, "USER_REQUESTED");
  assert.equal(event?.payload.status, "CANCELLED");
});

test("journey-order confirmation publishes WaitlistFulfilled with journeyOrderRef", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join({ accountId: "acc", travelerRef: "tvl", segmentRef: "seg", departureDate: "2026-07-20", travelClass: "SECOND", paymentGuaranteeRef: "pay-auth-1", itineraryRef: "itn", intentFingerprint: "intent" });

  const result = await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1, capacityReleaseRef: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c123" });
  assert.equal(result.promoted[0]?.status, "MATCHING");
  assert.equal(result.promoted[0]?.journeyOrderRef, `ord-${entry.waitlistRequestId}`);
  assert.equal(publisher.findByEventType("WaitlistFulfilled").length, 0);

  await service.handleJourneyOrderConfirmed(`ord-${entry.waitlistRequestId}`);

  const fulfilled = publisher.findByEventType("WaitlistFulfilled")[0];
  assert.equal(fulfilled?.eventId, deterministicEventId(`waitlist:WaitlistFulfilled:${entry.waitlistRequestId}:4`));
  assert.equal(fulfilled?.payload.journeyOrderRef, `ord-${entry.waitlistRequestId}`);
  assert.equal(fulfilled?.payload.status, "FULFILLED");
});
