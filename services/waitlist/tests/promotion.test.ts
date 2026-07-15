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

test("CapacityReleased starts matching and creates journey order for highest-priority queued request", async () => {
  const repository = new InMemoryWaitlistRepository();
  const publisher = new InMemoryEventPublisher();
  const fare = new StubFarePricing();
  const capacity = new StubCapacity();
  const service = new WaitlistApplicationService(repository, publisher, fare, capacity, new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const regular = await service.join(contractRequest({ travelerRef: "t1", loyaltyTier: "NONE" }));
  const platinum = await service.join(contractRequest({ travelerRef: "t2", loyaltyTier: "PLATINUM" }));

  const result = await service.handleCapacityFreed({ eventId: "cap-1", segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });

  assert.equal(result.promoted[0]?.entryId, platinum.waitlistRequestId);
  const promoted = await service.get(platinum.waitlistRequestId);
  assert.equal(promoted.status, "MATCHING");
  assert.equal(promoted.journeyOrderRef, `ord-${platinum.waitlistRequestId}`);
  assert.equal((await service.get(regular.waitlistRequestId)).status, "QUEUED");
  assert.equal(publisher.findByEventType("WaitlistMatchStarted").length, 1);
  assert.equal(publisher.findByEventType("WaitlistHoldAuthorized").length, 1);
});

test("deadline expiry publishes WaitlistExpired", async () => {
  let current = new Date("2026-01-01T00:00:00.000Z");
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => current, new StubOfferManagement());
  const entry = await service.join(contractRequest({ travelerRef: "t1", deadline: "2026-01-01T00:15:00.000Z" }));

  current = new Date("2026-01-01T00:16:00.000Z");
  await service.expireDueRequests();

  assert.equal((await service.get(entry.waitlistRequestId)).status, "EXPIRED");
  assert.equal(publisher.findByEventType("WaitlistExpired").length, 1);
});

test("journey order confirmation fulfills matching request", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join(contractRequest({ travelerRef: "t1" }));
  await service.handleCapacityFreed({ eventId: "cap-1", segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });
  await service.handleJourneyOrderConfirmed({ eventId: "jo-confirmed", journeyOrderRef: `ord-${entry.waitlistRequestId}` });
  assert.equal((await service.get(entry.waitlistRequestId)).status, "FULFILLED");
  assert.equal(publisher.findByEventType("WaitlistFulfilled").length, 1);
});

test("journey order cancellation requeues with associated order reference", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join(contractRequest({ travelerRef: "t1" }));
  await service.handleCapacityFreed({ eventId: "cap-1", segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });
  await service.handleJourneyOrderCancelled({ eventId: "jo-cancelled", journeyOrderRef: `ord-${entry.waitlistRequestId}` });

  const current = await service.get(entry.waitlistRequestId);
  const queuedEvents = publisher.findByEventType("WaitlistQueued");
  assert.equal(current.status, "QUEUED");
  assert.equal(current.journeyOrderRef, `ord-${entry.waitlistRequestId}`);
  assert.equal(queuedEvents.at(-1)?.payload.journeyOrderRef, `ord-${entry.waitlistRequestId}`);
});

test("legacy accept does not mutate request when journey order creation fails", async () => {
  class FirstOrderSucceedsThenFails implements JourneyOrderClient {
    private calls = 0;
    async createOrder(entry: WaitlistEntry) {
      this.calls += 1;
      if (this.calls > 1) throw new Error("downstream unavailable");
      return { orderId: `ord-${entry.entryId}`, seatAssignment: null };
    }
  }
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), new InMemoryEventPublisher(), new StubFarePricing(), new StubCapacity(), new FirstOrderSucceedsThenFails(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join(contractRequest({ travelerRef: "t1" }));
  await service.handleCapacityFreed({ eventId: "cap-1", segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });

  await assert.rejects(() => service.accept(entry.waitlistRequestId), /downstream unavailable/);

  const current = await service.get(entry.waitlistRequestId);
  assert.equal(current.status, "MATCHING");
  assert.equal(current.journeyOrderRef, `ord-${entry.waitlistRequestId}`);
});

function contractRequest(overrides: Partial<Parameters<WaitlistApplicationService["join"]>[0]>) {
  return {
    accountId: "acc",
    travelerRef: "t1",
    segmentRef: "seg",
    departureDate: "2026-07-20",
    travelClass: "SECOND" as const,
    deadline: "2026-07-20T00:00:00.000Z",
    paymentGuaranteeRef: "pay-auth-1",
    itineraryRef: "itin-1",
    intentFingerprint: `intent-${overrides.travelerRef ?? "t1"}`,
    loyaltyTier: "PLATINUM" as const,
    tripCount: 0,
    daysBefore: 20,
    ...overrides,
  };
}
