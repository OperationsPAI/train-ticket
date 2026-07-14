import assert from "node:assert/strict";
import test from "node:test";
import { PriorityCalculator, WaitlistEntry, WaitlistQueue } from "../src/domain.js";

test("platinum member scores higher than non-member", () => {
  const platinum = PriorityCalculator.calculate({ loyaltyTier: "PLATINUM", tripCount: 2, daysBefore: 3, groupSize: 1, fareClass: "SECOND", specialStatus: "NONE" });
  const regular = PriorityCalculator.calculate({ loyaltyTier: "NONE", tripCount: 2, daysBefore: 3, groupSize: 1, fareClass: "SECOND", specialStatus: "NONE" });
  assert.equal(platinum, 58);
  assert.equal(regular, 28);
  assert.ok(platinum > regular);
});

test("queue orders by priority descending then createdAt ascending", () => {
  const older = WaitlistEntry.create({ entryId: "wl-old", accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", priority: { loyaltyTier: "GOLD", tripCount: 10, daysBefore: 10 }, itineraryRef: "itin", deadline: new Date("2026-07-20T00:00:00.000Z"), paymentGuaranteeRef: "pay-auth-test", intentFingerprint: "fp-test", createdAt: new Date("2026-01-01T00:00:00.000Z") });
  const newer = WaitlistEntry.create({ entryId: "wl-new", accountId: "acc", travelerRefs: ["t2"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", priority: { loyaltyTier: "GOLD", tripCount: 10, daysBefore: 10 }, itineraryRef: "itin", deadline: new Date("2026-07-20T00:00:00.000Z"), paymentGuaranteeRef: "pay-auth-test", intentFingerprint: "fp-test-2", createdAt: new Date("2026-01-01T00:01:00.000Z") });
  const platinum = WaitlistEntry.create({ entryId: "wl-vip", accountId: "acc", travelerRefs: ["t3"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", priority: { loyaltyTier: "PLATINUM", tripCount: 10, daysBefore: 10 }, itineraryRef: "itin", deadline: new Date("2026-07-20T00:00:00.000Z"), paymentGuaranteeRef: "pay-auth-test", intentFingerprint: "fp-test-3", createdAt: new Date("2026-01-01T00:02:00.000Z") });
  const queue = new WaitlistQueue("seg", "2026-07-20", "SECOND", [newer, older, platinum]);
  assert.deepEqual(queue.queuedEntries().map((entry) => entry.entryId), ["wl-vip", "wl-old", "wl-new"]);
  assert.equal(queue.positionOf("wl-old"), 2);
});
