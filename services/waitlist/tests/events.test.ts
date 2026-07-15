import assert from "node:assert/strict";
import test from "node:test";
import { InMemoryEventPublisher } from "@trainticket/ts-kit";
import { InMemoryWaitlistRepository, WaitlistApplicationService, type JourneyOrderClient } from "../src/application.js";
import type { CapacityAvailabilityClient, FarePricingClient, OfferManagementClient } from "../src/promotion.js";
import type { WaitlistEntry } from "../src/domain.js";
import { waitlistEventIdSeed } from "../src/publisher.js";

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

test("join publishes deterministic created, payment authorization, and queued facts", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());

  const request = await service.join({ accountId: "acc", travelerRef: "tvl", segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", paymentGuaranteeRef: "pay-auth-1", itineraryRef: "itn", intentFingerprint: "intent" }, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c123");

  assert.deepEqual(publisher.envelopes.map((event) => event.eventType), ["WaitlistRequestCreated", "WaitlistPaymentAuthorizationRequested", "WaitlistQueued"]);
  assert.deepEqual(publisher.envelopes.map((event) => event.eventId), [
    waitlistEventIdSeed("WaitlistRequestCreated", request.waitlistRequestId, 1),
    waitlistEventIdSeed("WaitlistPaymentAuthorizationRequested", request.waitlistRequestId, 2),
    waitlistEventIdSeed("WaitlistQueued", request.waitlistRequestId, 3),
  ]);
  assert.deepEqual(publisher.findByEventType("WaitlistQueued")[0]?.payload, {
    waitlistRequestId: request.waitlistRequestId,
    accountId: "acc",
    travelerRef: "tvl",
    segmentRef: "seg",
    travelClass: "SECOND",
    itineraryRef: "itn",
    intentFingerprint: "intent",
    queuedAt: publisher.envelopes[2]?.occurredAt,
    status: "QUEUED",
  });
});

test("cancel publishes WaitlistCancelled with deterministic id", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const request = await service.join({ accountId: "acc", travelerRef: "tvl", segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", paymentGuaranteeRef: "pay-auth-1", itineraryRef: "itn", intentFingerprint: "intent" });

  await service.cancel(request.waitlistRequestId, "USER_CHANGED_PLANS");

  const cancelled = publisher.findByEventType("WaitlistCancelled")[0];
  assert.equal(cancelled?.eventId, waitlistEventIdSeed("WaitlistCancelled", request.waitlistRequestId, 4));
  assert.equal(cancelled?.payload.reason, "USER_CHANGED_PLANS");
  assert.equal(cancelled?.payload.status, "CANCELLED");
});

test("journey order cancellation requeues with journeyOrderRef still present in event", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const request = await service.join({ accountId: "acc", travelerRef: "tvl", segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", paymentGuaranteeRef: "pay-auth-1", itineraryRef: "itn", intentFingerprint: "intent" });
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1, eventId: "evt-capacity-1" });
  const order = await service.accept(request.waitlistRequestId);

  await service.handleJourneyOrderCancelled(order.orderId);

  const requeued = publisher.findByEventType("WaitlistQueued").at(-1);
  assert.equal(requeued?.payload.journeyOrderRef, order.orderId);
  assert.equal(requeued?.payload.status, "QUEUED");
});

test("journey order confirmation publishes WaitlistFulfilled", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const request = await service.join({ accountId: "acc", travelerRef: "tvl", segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", paymentGuaranteeRef: "pay-auth-1", itineraryRef: "itn", intentFingerprint: "intent" });
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1, eventId: "evt-capacity-1" });
  const order = await service.accept(request.waitlistRequestId);

  await service.handleJourneyOrderConfirmed(order.orderId);

  const fulfilled = publisher.findByEventType("WaitlistFulfilled")[0];
  assert.equal(fulfilled?.payload.journeyOrderRef, order.orderId);
  assert.equal(fulfilled?.payload.status, "FULFILLED");
});

test("expired queued request publishes WaitlistExpired with deterministic id", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-02T00:00:00.000Z"), new StubOfferManagement());
  const request = await service.join({ accountId: "acc", travelerRef: "tvl", segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", deadline: "2026-01-01T00:00:00.000Z", paymentGuaranteeRef: "pay-auth-1", itineraryRef: "itn", intentFingerprint: "intent" });

  await service.expireDueWaitlistRequests();

  const expired = publisher.findByEventType("WaitlistExpired")[0];
  assert.equal(expired?.eventId, waitlistEventIdSeed("WaitlistExpired", request.waitlistRequestId, 4));
  assert.equal(expired?.payload.waitlistRequestId, request.waitlistRequestId);
  assert.equal(expired?.payload.deadline, "2026-01-01T00:00:00.000Z");
  assert.equal(expired?.payload.expiredAt, "2026-01-02T00:00:00.000Z");
  assert.equal(expired?.payload.status, "EXPIRED");
  assert.equal((await service.get(request.waitlistRequestId)).status, "EXPIRED");
});
