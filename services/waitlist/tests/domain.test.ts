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
  const older = queuedEntry("wl-old", "t1", "GOLD", new Date("2026-01-01T00:00:00.000Z"));
  const newer = queuedEntry("wl-new", "t2", "GOLD", new Date("2026-01-01T00:01:00.000Z"));
  const platinum = queuedEntry("wl-vip", "t3", "PLATINUM", new Date("2026-01-01T00:02:00.000Z"));
  const queue = new WaitlistQueue("seg", "2026-07-20", "SECOND", [newer, older, platinum]);
  assert.deepEqual(queue.queuedEntries().map((entry) => entry.entryId), ["wl-vip", "wl-old", "wl-new"]);
  assert.equal(queue.positionOf("wl-old"), 2);
});

function queuedEntry(entryId: string, travelerRef: string, loyaltyTier: "PLATINUM" | "GOLD", createdAt: Date) {
  const entry = WaitlistEntry.create({ entryId, accountId: "acc", travelerRef, segmentRef: "seg", departureDate: "2026-07-20", deadline: "2026-07-20T23:59:59.000Z", travelClass: "SECOND", paymentGuaranteeRef: `pay-auth-${travelerRef}`, itineraryRef: `itin-${travelerRef}`, intentFingerprint: `intent-${travelerRef}`, priority: { loyaltyTier, tripCount: 10, daysBefore: 10 }, createdAt });
  entry.authorizePayment(createdAt);
  entry.enqueue(createdAt);
  return entry;
}
