import assert from "node:assert/strict";
import test from "node:test";
import { InMemoryEventPublisher } from "@trainticket/ts-kit";
import { InMemoryWaitlistRepository, WaitlistApplicationService, type JourneyOrderClient } from "../src/application.js";
import type { CapacityAvailabilityClient, FarePricingClient, OfferManagementClient } from "../src/promotion.js";
import type { WaitlistEntry } from "../src/domain.js";

class StubFarePricing implements FarePricingClient {
  public calls: string[] = [];
  async quote(entry: WaitlistEntry) { this.calls.push(entry.entryId); return { fareQuoteId: `fq-${entry.entryId}` }; }
}

class StubOfferManagement implements OfferManagementClient {
  async createOffer(entry: WaitlistEntry) { return { offerId: `off-${entry.entryId}`, offerVersion: 1 }; }
}

class StubCapacity implements CapacityAvailabilityClient {
  public holds: string[] = [];
  public released: string[] = [];
  async hold(entry: WaitlistEntry) { const capacityHoldId = `hold-${entry.entryId}`; this.holds.push(capacityHoldId); return { capacityHoldId }; }
  async releaseHold(_entry: WaitlistEntry, capacityHoldId: string) { this.released.push(capacityHoldId); }
}

class StubJourneyOrder implements JourneyOrderClient {
  async createOrder(entry: WaitlistEntry) { return { orderId: `ord-${entry.entryId}`, seatAssignment: { segmentRef: entry.segmentRef } }; }
}

test("WaitlistCapacityFreed promotes highest-priority queued entry", async () => {
  const repository = new InMemoryWaitlistRepository();
  const publisher = new InMemoryEventPublisher();
  const fare = new StubFarePricing();
  const capacity = new StubCapacity();
  const service = new WaitlistApplicationService(repository, publisher, fare, capacity, new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const regular = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "NONE", tripCount: 0, daysBefore: 20 });
  const platinum = await service.join({ accountId: "acc", travelerRefs: ["t2"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20 });

  const result = await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });

  assert.equal(result.promoted[0]?.waitlistRequestId, platinum.waitlistRequestId);
  assert.equal((await service.get(platinum.waitlistRequestId)).status, "MATCHING");
  assert.equal((await service.get(regular.waitlistRequestId)).status, "QUEUED");
  assert.equal(publisher.findByEventType("WaitlistEntryPromoted").length, 1);
});

test("expired matching attempt returns to queue, releases hold, and frees capacity", async () => {
  let current = new Date("2026-01-01T00:00:00.000Z");
  const repository = new InMemoryWaitlistRepository();
  const publisher = new InMemoryEventPublisher();
  const capacity = new StubCapacity();
  const service = new WaitlistApplicationService(repository, publisher, new StubFarePricing(), capacity, new StubJourneyOrder(), () => current, new StubOfferManagement());
  const first = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20 });
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });

  current = new Date("2026-01-01T00:16:00.000Z");
  await service.expireDueOffers();

  assert.equal((await service.get(first.waitlistRequestId)).status, "MATCHING");
  assert.deepEqual(capacity.released, [`hold-${first.waitlistRequestId}`]);
  assert.equal(publisher.findByEventType("WaitlistOfferExpired").length, 1);
  assert.equal(publisher.findByEventType("WaitlistEntryPromoted").length, 2);
});

test("accept promotion creates journey order and publishes accepted event", async () => {
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), new InMemoryEventPublisher(), new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20 });
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });
  const order = await service.accept(entry.waitlistRequestId, { paymentMethodRef: "pm-1" });
  assert.equal(order.orderId, `ord-${entry.waitlistRequestId}`);
  assert.equal((await service.get(entry.waitlistRequestId)).status, "FULFILLED");
});

test("accept does not mutate entry when journey order creation fails", async () => {
  class FailingJourneyOrder implements JourneyOrderClient {
    async createOrder(): Promise<never> { throw new Error("downstream unavailable"); }
  }
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), new InMemoryEventPublisher(), new StubFarePricing(), new StubCapacity(), new FailingJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20 });
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });

  await assert.rejects(() => service.accept(entry.waitlistRequestId), /downstream unavailable/);

  assert.equal((await service.get(entry.waitlistRequestId)).status, "MATCHING");
});
