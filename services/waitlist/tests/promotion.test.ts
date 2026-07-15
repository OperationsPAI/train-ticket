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
class StubOfferManagement implements OfferManagementClient { async createOffer(entry: WaitlistEntry) { return { offerId: `off-${entry.entryId}`, offerVersion: 1 }; } }
class StubCapacity implements CapacityAvailabilityClient {
  public holds: string[] = [];
  public released: string[] = [];
  async hold(entry: WaitlistEntry) { const capacityHoldId = `hold-${entry.entryId}`; this.holds.push(capacityHoldId); return { capacityHoldId }; }
  async releaseHold(_entry: WaitlistEntry, capacityHoldId: string) { this.released.push(capacityHoldId); }
}
class StubJourneyOrder implements JourneyOrderClient { async createOrder(entry: WaitlistEntry) { return { orderId: `ord-${entry.entryId}`, seatAssignment: { segmentRef: entry.segmentRef } }; } }

const baseRequest = (travelerRef: string, loyaltyTier: "PLATINUM" | "GOLD" | "NONE" = "NONE") => ({ accountId: "acc", travelerRef, segmentRef: "seg", departureDate: "2026-07-20", deadline: "2026-07-20T23:59:59.000Z", travelClass: "SECOND" as const, paymentGuaranteeRef: `pay-auth-${travelerRef}`, itineraryRef: `itin-${travelerRef}`, intentFingerprint: `intent-${travelerRef}`, loyaltyTier, tripCount: 0, daysBefore: 20 });

test("CapacityReleased starts matching highest-priority queued request", async () => {
  const repository = new InMemoryWaitlistRepository();
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(repository, publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const regular = await service.join(baseRequest("t1", "NONE"));
  const platinum = await service.join(baseRequest("t2", "PLATINUM"));

  const result = await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1, eventId: "evt-capacity-1" });

  assert.equal(result.promoted[0]?.entryId, platinum.waitlistRequestId);
  assert.equal((await service.get(platinum.waitlistRequestId)).status, "MATCHING");
  assert.equal((await service.get(regular.waitlistRequestId)).status, "QUEUED");
  assert.equal(publisher.findByEventType("WaitlistMatchStarted").length, 1);
  assert.equal(publisher.findByEventType("WaitlistHoldAuthorized").length, 1);
  assert.match(publisher.findByEventType("WaitlistMatchStarted")[0].eventId, new RegExp(`^waitlist:WaitlistMatchStarted:${platinum.waitlistRequestId}:4$`));
});

test("deadline expiry releases hold, publishes WaitlistExpired, and promotes next queued request", async () => {
  let current = new Date("2026-01-01T00:00:00.000Z");
  const publisher = new InMemoryEventPublisher();
  const capacity = new StubCapacity();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), capacity, new StubJourneyOrder(), () => current, new StubOfferManagement());
  const first = await service.join(baseRequest("t1", "PLATINUM"));
  const second = await service.join({ ...baseRequest("t2", "GOLD"), deadline: "2026-07-22T23:59:59.000Z" });
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });

  current = new Date("2026-07-21T00:00:00.000Z");
  await service.expireDueOffers();

  assert.equal((await service.get(first.waitlistRequestId)).status, "EXPIRED");
  assert.equal((await service.get(second.waitlistRequestId)).status, "MATCHING");
  assert.deepEqual(capacity.released, [`hold-${first.waitlistRequestId}`]);
  assert.equal(publisher.findByEventType("WaitlistExpired").length, 1);
  assert.equal(publisher.findByEventType("WaitlistMatchStarted").length, 2);
});

test("accept promotion creates journey order and records pending order until JourneyOrderConfirmed", async () => {
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), new InMemoryEventPublisher(), new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join(baseRequest("t1", "PLATINUM"));
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });
  const order = await service.accept(entry.waitlistRequestId, { paymentMethodRef: "pm-1" });
  assert.equal(order.orderId, `ord-${entry.waitlistRequestId}`);
  assert.equal((await service.get(entry.waitlistRequestId)).status, "MATCHING");

  await service.handleJourneyOrderConfirmed({ orderId: order.orderId, occurredAt: "2026-01-01T00:01:00.000Z" });

  assert.equal((await service.get(entry.waitlistRequestId)).status, "FULFILLED");
});

test("JourneyOrderCancelled requeues matching request and publishes WaitlistQueued", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join(baseRequest("t3", "PLATINUM"));
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });
  const order = await service.accept(entry.waitlistRequestId);

  await service.handleJourneyOrderCancelled({ orderId: order.orderId, reason: "PAYMENT_FAILED", occurredAt: "2026-01-01T00:02:00.000Z" });

  assert.equal((await service.get(entry.waitlistRequestId)).status, "QUEUED");
  assert.equal(publisher.findByEventType("WaitlistQueued").at(-1)?.payload.requeueReason, "PAYMENT_FAILED");
});

test("accept does not mutate entry when journey order creation fails", async () => {
  class FailingJourneyOrder implements JourneyOrderClient { async createOrder(): Promise<never> { throw new Error("downstream unavailable"); } }
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), new InMemoryEventPublisher(), new StubFarePricing(), new StubCapacity(), new FailingJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join(baseRequest("t1", "PLATINUM"));
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });

  await assert.rejects(() => service.accept(entry.waitlistRequestId), /downstream unavailable/);

  assert.equal((await service.get(entry.waitlistRequestId)).status, "MATCHING");
});
