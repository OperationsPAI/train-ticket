import assert from "node:assert/strict";
import test from "node:test";
import { InMemoryEventPublisher } from "@trainticket/ts-kit";
import { InMemoryWaitlistRepository, WaitlistApplicationService, type JourneyOrderClient } from "../src/application.js";
import type { CapacityAvailabilityClient, FarePricingClient } from "../src/promotion.js";
import type { WaitlistEntry } from "../src/domain.js";

class StubFarePricing implements FarePricingClient {
  public calls: string[] = [];
  async quote(entry: WaitlistEntry) { this.calls.push(entry.entryId); return { fareQuoteId: `fq-${entry.entryId}` }; }
}

class StubCapacity implements CapacityAvailabilityClient {
  public holds: string[] = [];
  public released: string[] = [];
  async hold(entry: WaitlistEntry) { const capacityHoldId = `hold-${entry.entryId}`; this.holds.push(capacityHoldId); return { capacityHoldId }; }
  async releaseHold(capacityHoldId: string) { this.released.push(capacityHoldId); }
}

class StubJourneyOrder implements JourneyOrderClient {
  async createOrder(entry: WaitlistEntry) { return { orderId: `ord-${entry.entryId}`, seatAssignment: { segmentRef: entry.segmentRef } }; }
}

test("WaitlistCapacityFreed promotes highest-priority queued entry", async () => {
  const repository = new InMemoryWaitlistRepository();
  const publisher = new InMemoryEventPublisher();
  const fare = new StubFarePricing();
  const capacity = new StubCapacity();
  const service = new WaitlistApplicationService(repository, publisher, fare, capacity, new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"));
  const regular = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "NONE", tripCount: 0, daysBefore: 20 });
  const platinum = await service.join({ accountId: "acc", travelerRefs: ["t2"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20 });

  const result = await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });

  assert.equal(result.promoted[0]?.entryId, platinum.entryId);
  assert.equal((await service.get(platinum.entryId)).status, "OFFERED");
  assert.equal((await service.get(regular.entryId)).status, "QUEUED");
  assert.equal(publisher.findByEventType("WaitlistEntryPromoted").length, 1);
});

test("expired offer releases hold and promotes next queued entry", async () => {
  let current = new Date("2026-01-01T00:00:00.000Z");
  const repository = new InMemoryWaitlistRepository();
  const publisher = new InMemoryEventPublisher();
  const capacity = new StubCapacity();
  const service = new WaitlistApplicationService(repository, publisher, new StubFarePricing(), capacity, new StubJourneyOrder(), () => current);
  const first = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20 });
  const second = await service.join({ accountId: "acc", travelerRefs: ["t2"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "GOLD", tripCount: 0, daysBefore: 20 });
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });

  current = new Date("2026-01-01T00:16:00.000Z");
  await service.expireDueOffers();

  assert.equal((await service.get(first.entryId)).status, "EXPIRED");
  assert.equal((await service.get(second.entryId)).status, "OFFERED");
  assert.deepEqual(capacity.released, [`hold-${first.entryId}`]);
  assert.equal(publisher.findByEventType("WaitlistOfferExpired").length, 1);
  assert.equal(publisher.findByEventType("WaitlistEntryPromoted").length, 2);
});

test("accept promotion creates journey order and publishes accepted event", async () => {
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), new InMemoryEventPublisher(), new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"));
  const entry = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20 });
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });
  const order = await service.accept(entry.entryId, { paymentMethodRef: "pm-1" });
  assert.equal(order.orderId, `ord-${entry.entryId}`);
  assert.equal((await service.get(entry.entryId)).status, "ACCEPTED");
});
