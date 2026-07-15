import assert from "node:assert/strict";
import test from "node:test";
import { DomainError, PriorityCalculator, WaitlistEntry, WaitlistQueue } from "../src/domain.js";

test("platinum member scores higher than non-member", () => {
  const platinum = PriorityCalculator.calculate({ loyaltyTier: "PLATINUM", tripCount: 2, daysBefore: 3, groupSize: 1, fareClass: "SECOND", specialStatus: "NONE" });
  const regular = PriorityCalculator.calculate({ loyaltyTier: "NONE", tripCount: 2, daysBefore: 3, groupSize: 1, fareClass: "SECOND", specialStatus: "NONE" });
  assert.equal(platinum, 58);
  assert.equal(regular, 28);
  assert.ok(platinum > regular);
});

test("queue orders by priority descending then createdAt ascending", () => {
  const older = WaitlistEntry.create({ entryId: "wl-old", accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", priority: { loyaltyTier: "GOLD", tripCount: 10, daysBefore: 10 }, createdAt: new Date("2026-01-01T00:00:00.000Z") });
  const newer = WaitlistEntry.create({ entryId: "wl-new", accountId: "acc", travelerRefs: ["t2"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", priority: { loyaltyTier: "GOLD", tripCount: 10, daysBefore: 10 }, createdAt: new Date("2026-01-01T00:01:00.000Z") });
  const platinum = WaitlistEntry.create({ entryId: "wl-vip", accountId: "acc", travelerRefs: ["t3"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", priority: { loyaltyTier: "PLATINUM", tripCount: 10, daysBefore: 10 }, createdAt: new Date("2026-01-01T00:02:00.000Z") });
  const queue = new WaitlistQueue("seg", "2026-07-20", "SECOND", [newer, older, platinum]);
  assert.deepEqual(queue.queuedEntries().map((entry) => entry.entryId), ["wl-vip", "wl-old", "wl-new"]);
  assert.equal(queue.positionOf("wl-old"), 2);
});

test("matching entries return to queue instead of expiring or cancelling directly", () => {
  const entry = WaitlistEntry.create({ entryId: "wl-match", accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", priority: { loyaltyTier: "GOLD", tripCount: 10, daysBefore: 10 }, createdAt: new Date("2026-01-01T00:00:00.000Z") });
  entry.offer("offer-1", 1, "fare-1", "hold-1", new Date("2026-01-01T00:00:00.000Z"), new Date("2026-01-01T00:15:00.000Z"));

  assert.throws(() => entry.expire(), DomainError);
  assert.throws(() => entry.cancel(), DomainError);

  entry.returnToQueue();

  assert.equal(entry.status, "QUEUED");
  assert.equal(entry.offerId, undefined);
  assert.equal(entry.offerExpiresAt, null);
});
