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

test("matching entries return to queue instead of cancelling directly", () => {
  const entry = WaitlistEntry.create({ entryId: "wl-match", accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", priority: { loyaltyTier: "GOLD", tripCount: 10, daysBefore: 10 }, createdAt: new Date("2026-01-01T00:00:00.000Z") });
  entry.offer("offer-1", 1, "fare-1", "hold-1", new Date("2026-01-01T00:00:00.000Z"), new Date("2026-01-01T00:15:00.000Z"));

  assert.throws(() => entry.cancel(), DomainError);

  entry.returnToQueue();

  assert.equal(entry.status, "QUEUED");
  assert.equal(entry.offerId, undefined);
  assert.equal(entry.offerExpiresAt, null);
});

test("a requeued entry rotates fulfillment effect keys for the next promotion", () => {
  const entry = WaitlistEntry.create({ entryId: "wl-rematch", accountId: "acc", travelerRefs: ["t1", "t2"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", priority: {} });
  entry.offer("offer-1", 1, "fare-1", undefined, new Date("2026-01-01T00:00:00.000Z"), new Date("2026-01-01T00:15:00.000Z"));
  entry.linkJourneyOrder(new Date("2026-01-01T00:01:00.000Z"), "ord-1", { currency: "CNY", minorUnits: 100 });
  const first = entry.fulfillment;

  entry.returnToQueue();
  const second = entry.fulfillment;

  assert.notEqual(second.paymentCreateIdempotencyKey, first.paymentCreateIdempotencyKey);
  assert.notEqual(second.paymentCaptureIdempotencyKey, first.paymentCaptureIdempotencyKey);
  assert.notEqual(second.orderCancelIdempotencyKey, first.orderCancelIdempotencyKey);
  assert.notEqual(second.travelers[0]?.segmentBookingId, first.travelers[0]?.segmentBookingId);
  assert.notEqual(second.travelers[0]?.entitlementIdempotencyKey, first.travelers[0]?.entitlementIdempotencyKey);
  assert.deepEqual(WaitlistEntry.fromSnapshot(entry.toSnapshot()).fulfillment, second);
});

test("an authoritative confirmed order fulfills a suspended linked request", () => {
  const entry = WaitlistEntry.create({ entryId: "wl-confirm-suspended", accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", priority: {} });
  entry.offer("offer-1", 1, "fare-1", undefined, new Date("2026-01-01T00:00:00.000Z"), new Date("2026-01-01T00:15:00.000Z"));
  entry.linkJourneyOrder(new Date("2026-01-01T00:01:00.000Z"), "ord-1", { currency: "CNY", minorUnits: 100 });
  entry.suspend();

  entry.confirmJourneyOrder("ord-1");

  assert.equal(entry.status, "FULFILLED");
});

test("offer acceptance rejects the exact expiry instant", () => {
  const entry = WaitlistEntry.create({ entryId: "wl-expiry", accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND_CLASS", priority: {} });
  const now = new Date("2026-01-01T00:00:00.000Z");
  const expiresAt = new Date("2026-01-01T00:10:00.000Z");
  entry.offer("offer-1", 1, "fare-1", undefined, now, expiresAt);

  assert.throws(() => entry.ensureOfferAcceptable(expiresAt), /expired/u);
});

test("close only archives terminal fulfilled expired or cancelled entries", () => {
  const fulfilled = WaitlistEntry.fromSnapshot({
    entryId: "wl-fulfilled",
    accountId: "acc",
    travelerRefs: ["t1"],
    segmentRef: "seg",
    scheduledServiceRef: "ss-1",
    departureDate: "2026-07-20",
    seatClass: "SECOND_CLASS",
    interval: { fromSeq: 0, toSeq: 1 },
    stopSequenceVersion: "sha256:stops-1",
    priorityScore: 50,
    status: "FULFILLED",
    queuePosition: 0,
    createdAt: "2026-01-01T00:00:00.000Z",
    offeredAt: null,
    offerExpiresAt: null,
    deadline: "2026-07-20T00:00:00.000Z",
    paymentGuaranteeRef: "pi-1",
    intentFingerprint: "intent-1",
  });
  const expired = WaitlistEntry.fromSnapshot({ ...fulfilled.toSnapshot(), entryId: "wl-expired", status: "EXPIRED" });
  const cancelled = WaitlistEntry.fromSnapshot({ ...fulfilled.toSnapshot(), entryId: "wl-cancelled", status: "CANCELLED" });
  const queued = WaitlistEntry.create({ entryId: "wl-queued", accountId: "acc", travelerRefs: ["t2"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", priority: { groupSize: 1, fareClass: "SECOND" } });

  fulfilled.close();
  expired.close();
  cancelled.close();

  assert.equal(fulfilled.status, "CLOSED");
  assert.equal(expired.status, "CLOSED");
  assert.equal(cancelled.status, "CLOSED");
  assert.throws(() => queued.close(), DomainError);
  assert.throws(() => fulfilled.close(), DomainError);
});
