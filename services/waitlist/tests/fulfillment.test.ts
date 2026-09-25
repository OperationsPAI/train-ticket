import assert from "node:assert/strict";
import test from "node:test";
import { InMemoryEventPublisher, isUuidV7 } from "@trainticket/ts-kit";
import { WaitlistApplicationService } from "../src/application.js";
import { WaitlistEntry, type FulfillmentTraveler, type WaitlistEntrySnapshot } from "../src/domain.js";
import type { EntitlementFacts, OrderFacts, PaymentFacts, ReservationFacts, SagaFacts, WaitlistFulfillmentClient } from "../src/fulfillment.js";
import type { WaitlistCapacityFreed, WaitlistMatchCriteria, WaitlistRepository } from "../src/promotion.js";

const NOW = new Date("2026-09-25T00:00:00.000Z");
const ORDER_AMOUNT = { currency: "CNY", minorUnits: 10_750 } as const;

class SnapshotRepository implements WaitlistRepository {
  protected snapshot: WaitlistEntrySnapshot;
  protected version = 1;
  public restoredSlots = 0;

  constructor(entry: WaitlistEntry) {
    entry.markPersisted(this.version);
    this.snapshot = entry.toSnapshot();
  }

  async add(entry: WaitlistEntry) { this.snapshot = entry.toSnapshot(); return this.snapshot; }
  async get(entryId: string) { return entryId === this.snapshot.entryId ? this.restore() : undefined; }
  async save(entry: WaitlistEntry) {
    this.version++;
    entry.markPersisted(this.version);
    this.snapshot = structuredClone(entry.toSnapshot());
    return structuredClone(this.snapshot);
  }
  async recordCapacityCredit(_event: WaitlistCapacityFreed) { return false; }
  async claimNextPromotion(_now: Date) { return undefined; }
  async findRecoverablePromotion() { return undefined; }
  async findRecoverableFulfillment(now: Date) {
    if (this.snapshot.status !== "MATCHING" || !this.snapshot.journeyOrderRef) return undefined;
    if (this.snapshot.fulfillment?.nextActionAt && new Date(this.snapshot.fulfillment.nextActionAt) > now) return undefined;
    return this.restore();
  }
  async restoreCapacityCredits(_entry: WaitlistEntry, slots = _entry.travelerRefs.length) { this.restoredSlots += slots; }
  async findTopQueued(_criteria: WaitlistMatchCriteria) { return undefined; }
  async findByJourneyOrderRef(orderId: string) { return this.snapshot.journeyOrderRef === orderId ? this.restore() : undefined; }
  async findExpiredOffers(_now: Date) { return []; }
  async findArchivable() { return []; }
  async queueFor(_segmentRef: string, _departureDate: string, _seatClass?: string) { return [structuredClone(this.snapshot)]; }
  async listByTraveler(travelerRef: string) { return this.snapshot.travelerRefs.includes(travelerRef) ? [structuredClone(this.snapshot)] : []; }
  current() { return structuredClone(this.snapshot); }

  protected restore(): WaitlistEntry {
    const entry = WaitlistEntry.fromSnapshot(structuredClone(this.snapshot));
    entry.markPersisted(this.version);
    return entry;
  }
}

class SaveFailureRepository extends SnapshotRepository {
  private failures = 0;

  failNextSave() { this.failures++; }

  override async save(entry: WaitlistEntry) {
    if (this.failures > 0) {
      this.failures--;
      throw new Error("simulated crash before waitlist checkpoint commit");
    }
    return super.save(entry);
  }
}

class DurableFulfillmentClient implements WaitlistFulfillmentClient {
  public orderStatus = "CREATED";
  public sagaStatusOverride?: string;
  public readonly reservationCalls: Array<{ travelerRef: string; key: string }> = [];
  public readonly paymentCreateCalls: string[] = [];
  public readonly paymentCaptureCalls: string[] = [];
  public readonly entitlementCalls: Array<{ travelerRef: string; key: string }> = [];
  public readonly releaseCalls: Array<{ holdId: string; key: string }> = [];
  public readonly orderCancelCalls: string[] = [];
  public readonly paymentCancelCalls: string[] = [];
  public reservationEffects = 0;
  public paymentCreateEffects = 0;
  public paymentCaptureEffects = 0;
  public entitlementEffects = 0;
  public captureDuringCancellation = false;
  protected readonly reservations = new Map<string, ReservationFacts>();
  protected readonly reservationKeys = new Map<string, ReservationFacts>();
  protected payment?: PaymentFacts;
  protected readonly paymentCreateKeys = new Map<string, PaymentFacts>();
  protected readonly paymentCaptureKeys = new Set<string>();
  protected readonly entitlements = new Map<string, EntitlementFacts>();
  protected readonly entitlementKeys = new Map<string, EntitlementFacts>();
  protected readonly releasedHolds = new Set<string>();

  constructor(protected readonly travelers: readonly string[], protected readonly autoConfirmOrder = true) {}

  async getOrder(orderId: string): Promise<OrderFacts> {
    return { orderId, accountId: "acc-1", offerId: "off-1", status: this.orderStatus, riskClear: true,
      travelerRefs: [...this.travelers], segmentRefs: ["seg-1"], amount: ORDER_AMOUNT };
  }

  async findSaga(orderId: string): Promise<SagaFacts> {
    return { sagaId: "saga-1", orderId, status: this.sagaStatusOverride ?? this.sagaStatus() };
  }

  async requestReservation(entry: WaitlistEntry, traveler: FulfillmentTraveler, _sagaId: string): Promise<ReservationFacts> {
    this.reservationCalls.push({ travelerRef: traveler.travelerRef, key: traveler.reservationIdempotencyKey });
    const replay = this.reservationKeys.get(traveler.reservationIdempotencyKey);
    if (replay) return replay;
    const reservation = this.reservation(entry, traveler, "CONFIRMED", `hold-${traveler.travelerRef}`);
    this.reservationEffects++;
    this.reservations.set(traveler.segmentBookingId, reservation);
    this.reservationKeys.set(traveler.reservationIdempotencyKey, reservation);
    return reservation;
  }

  async getReservation(traveler: FulfillmentTraveler) { return this.reservations.get(traveler.segmentBookingId); }

  async createPayment(entry: WaitlistEntry): Promise<PaymentFacts> {
    const key = entry.fulfillment.paymentCreateIdempotencyKey;
    this.paymentCreateCalls.push(key);
    const replay = this.paymentCreateKeys.get(key);
    if (replay) return replay;
    const payment = this.paymentFacts(entry, "CREATED");
    this.paymentCreateEffects++;
    this.payment = payment;
    this.paymentCreateKeys.set(key, payment);
    return payment;
  }

  async findPayment(orderId: string) { return this.payment?.businessRef === orderId ? this.payment : undefined; }
  async getPayment(paymentIntentId: string) { assert.equal(this.payment?.paymentIntentId, paymentIntentId); return this.payment!; }

  async capturePayment(entry: WaitlistEntry): Promise<PaymentFacts> {
    const key = entry.fulfillment.paymentCaptureIdempotencyKey;
    this.paymentCaptureCalls.push(key);
    if (!this.paymentCaptureKeys.has(key)) {
      this.paymentCaptureKeys.add(key);
      this.paymentCaptureEffects++;
      this.payment = this.paymentFacts(entry, "CAPTURED");
    }
    return this.payment!;
  }

  async cancelPayment(entry: WaitlistEntry) {
    this.paymentCancelCalls.push(entry.fulfillment.paymentCancelIdempotencyKey);
    if (this.payment) this.payment = { ...this.payment, status: this.captureDuringCancellation ? "CAPTURED" : "CANCELLED" };
    return this.payment;
  }

  async releaseCapacity(traveler: FulfillmentTraveler, capacityHoldId: string) {
    this.releaseCalls.push({ holdId: capacityHoldId, key: traveler.capacityReleaseIdempotencyKey });
    this.releasedHolds.add(capacityHoldId);
  }

  async cancelOrder(entry: WaitlistEntry) {
    this.orderCancelCalls.push(entry.fulfillment.orderCancelIdempotencyKey);
    this.orderStatus = "CANCELLED";
  }

  async issueEntitlement(entry: WaitlistEntry, traveler: FulfillmentTraveler): Promise<EntitlementFacts> {
    this.entitlementCalls.push({ travelerRef: traveler.travelerRef, key: traveler.entitlementIdempotencyKey });
    const replay = this.entitlementKeys.get(traveler.entitlementIdempotencyKey);
    if (replay) return replay;
    const entitlement = { entitlementId: `ent-${traveler.travelerRef}`, segmentBookingId: traveler.segmentBookingId,
      orderId: entry.journeyOrderRef!, status: "ISSUED" };
    this.entitlementEffects++;
    this.entitlements.set(traveler.travelerRef, entitlement);
    this.entitlementKeys.set(traveler.entitlementIdempotencyKey, entitlement);
    if (this.autoConfirmOrder && this.entitlements.size === this.travelers.length) this.orderStatus = "CONFIRMED";
    return entitlement;
  }

  seedReservation(entry: WaitlistEntry, traveler: FulfillmentTraveler, status: string, capacityHoldId?: string) {
    this.reservations.set(traveler.segmentBookingId, this.reservation(entry, traveler, status, capacityHoldId));
  }

  private reservation(entry: WaitlistEntry, traveler: FulfillmentTraveler, status: string, capacityHoldId?: string): ReservationFacts {
    return { segmentBookingId: traveler.segmentBookingId, orderId: entry.journeyOrderRef!, travelerRef: traveler.travelerRef,
      segmentRef: entry.segmentRef, status, capacityHoldId };
  }

  private paymentFacts(entry: WaitlistEntry, status: string): PaymentFacts {
    return { paymentIntentId: "pi-fulfillment", businessRef: entry.journeyOrderRef!, payerRef: entry.accountId,
      status, amount: entry.fulfillment.orderAmount! };
  }

  private sagaStatus(): string {
    if (this.payment?.status === "CAPTURED") return "TICKETING";
    if (this.reservations.size === this.travelers.length) return "WAITING_PAYMENT";
    return "RESERVING";
  }
}

function matchingEntry(travelerRefs: readonly string[] = ["tvl-1", "tvl-2"]): WaitlistEntry {
  const entry = WaitlistEntry.create({
    entryId: "wlr-0194f2e0-7b3e-7610-8284-5c26e8b0c200",
    accountId: "acc-1",
    travelerRefs,
    segmentRef: "seg-1",
    scheduledServiceRef: "svc-1",
    departureDate: "2026-10-20",
    seatClass: "SECOND_CLASS",
    interval: { fromSeq: 1, toSeq: 4 },
    stopSequenceVersion: "sha256:route-v1",
    priority: {},
    deadline: "2026-10-19T00:00:00.000Z",
  });
  entry.beginPromotion([{ capacityReleaseRef: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c201", slots: travelerRefs.length }]);
  entry.recordFareQuote("fq-1");
  entry.recordOrderAmount(ORDER_AMOUNT);
  entry.offer("off-1", 1, "fq-1", undefined, NOW, new Date("2026-09-25T00:15:00.000Z"));
  entry.linkJourneyOrder(NOW, "ord-0194f2e0-7b3e-7610-8284-5c26e8b0c202", ORDER_AMOUNT);
  return entry;
}

test("two-traveler waitlist fulfillment survives a service restart at every persisted checkpoint", async () => {
  const repository = new SnapshotRepository(matchingEntry());
  const publisher = new InMemoryEventPublisher();
  const client = new DurableFulfillmentClient(["tvl-1", "tvl-2"]);
  let now = NOW;

  for (let step = 0; step < 30 && repository.current().status !== "FULFILLED"; step++) {
    const restarted = new WaitlistApplicationService(repository, publisher, undefined, undefined, undefined, () => now, undefined, undefined, client);
    await restarted.advanceNextWork("corr-0194f2e0-7b3e-7610-8284-5c26e8b0c203");
    now = new Date(now.getTime() + 1_001);
  }

  const fulfilled = repository.current();
  assert.equal(fulfilled.status, "FULFILLED");
  assert.equal(fulfilled.fulfillment?.travelers.every((traveler) => traveler.reservationStatus === "CONFIRMED" && !!traveler.capacityHoldId && !!traveler.entitlementId), true);
  assert.equal(client.reservationEffects, 2);
  assert.equal(client.paymentCreateEffects, 1);
  assert.equal(client.paymentCaptureEffects, 1);
  assert.equal(client.entitlementEffects, 2);
  assert.equal(publisher.findByEventType("WaitlistFulfilled").length, 1);
  assert.equal(client.orderStatus, "CONFIRMED");
  for (const traveler of fulfilled.fulfillment!.travelers) {
    assert.equal(isUuidV7(traveler.reservationIdempotencyKey), true);
    assert.equal(isUuidV7(traveler.entitlementIdempotencyKey), true);
    assert.equal(isUuidV7(traveler.capacityReleaseIdempotencyKey), true);
  }
});

test("terminal order cleanup releases held seats and restores only traveler slots that never became holds", async () => {
  const entry = matchingEntry();
  const repository = new SnapshotRepository(entry);
  const client = new DurableFulfillmentClient(["tvl-1", "tvl-2"], false);
  client.orderStatus = "CANCELLED";
  client.seedReservation(entry, entry.fulfillment.travelers[0]!, "CONFIRMED", "hold-tvl-1");

  const service = new WaitlistApplicationService(repository, new InMemoryEventPublisher(), undefined, undefined, undefined, () => NOW, undefined, undefined, client);
  const result = await service.handleJourneyOrderCancelled(entry.journeyOrderRef!);

  assert.equal(result?.status, "QUEUED");
  assert.equal(repository.restoredSlots, 1);
  assert.deepEqual(client.releaseCalls.map((call) => call.holdId), ["hold-tvl-1"]);
  assert.equal(new Set(client.releaseCalls.map((call) => call.key)).size, 1);
});

test("external fulfillment effects remain exactly once when every local checkpoint save fails once", async () => {
  const repository = new SaveFailureRepository(matchingEntry());
  const publisher = new InMemoryEventPublisher();
  const client = new DurableFulfillmentClient(["tvl-1", "tvl-2"], false);
  let now = NOW;
  const restart = () => new WaitlistApplicationService(repository, publisher, undefined, undefined, undefined, () => now, undefined, undefined, client);
  const advance = async () => {
    const result = await restart().advanceNextWork("corr-0194f2e0-7b3e-7610-8284-5c26e8b0c204");
    now = new Date(now.getTime() + 1_001);
    return result;
  };
  const crashAfterEffect = async (effectCount: () => number) => {
    const before = effectCount();
    repository.failNextSave();
    await assert.rejects(advance, /simulated crash/u);
    assert.equal(effectCount(), before + 1);
    await advance();
    assert.equal(effectCount(), before + 1);
  };

  await advance(); // Persist the saga identity before injecting effect failures.
  await crashAfterEffect(() => client.reservationEffects);
  await crashAfterEffect(() => client.reservationEffects);
  await crashAfterEffect(() => client.paymentCreateEffects);
  await crashAfterEffect(() => client.paymentCaptureEffects);
  await crashAfterEffect(() => client.entitlementEffects);
  await crashAfterEffect(() => client.entitlementEffects);

  client.orderStatus = "CONFIRMED";
  await advance();

  assert.equal(repository.current().status, "FULFILLED");
  assert.equal(client.reservationEffects, 2);
  assert.equal(client.paymentCreateEffects, 1);
  assert.equal(client.paymentCaptureEffects, 1);
  assert.equal(client.entitlementEffects, 2);
  assert.equal(publisher.findByEventType("WaitlistFulfilled").length, 1);
  assert.equal(new Set(client.reservationCalls.filter((call) => call.travelerRef === "tvl-1").map((call) => call.key)).size, 1);
  assert.equal(new Set(client.reservationCalls.filter((call) => call.travelerRef === "tvl-2").map((call) => call.key)).size, 1);
  assert.equal(new Set(client.paymentCreateCalls).size, 1);
  assert.equal(new Set(client.paymentCaptureCalls).size, 1);
  assert.equal(new Set(client.entitlementCalls.filter((call) => call.travelerRef === "tvl-1").map((call) => call.key)).size, 1);
  assert.equal(new Set(client.entitlementCalls.filter((call) => call.travelerRef === "tvl-2").map((call) => call.key)).size, 1);
});

test("payment is not created after the waitlist authorization deadline", async () => {
  const entry = matchingEntry(["tvl-1"]);
  const repository = new SnapshotRepository(entry);
  const publisher = new InMemoryEventPublisher();
  const client = new DurableFulfillmentClient(["tvl-1"], false);
  client.seedReservation(entry, entry.fulfillment.travelers[0]!, "CONFIRMED", "hold-tvl-1");
  const afterDeadline = new Date("2026-10-19T00:00:00.000Z");
  const service = new WaitlistApplicationService(repository, publisher, undefined, undefined, undefined, () => afterDeadline, undefined, undefined, client);

  await service.advanceNextWork(); // Persist saga reference.
  await service.advanceNextWork(); // Recover reservation fact.
  await service.advanceNextWork(); // Abort instead of creating payment.

  assert.equal(client.paymentCreateEffects, 0);
  assert.equal(client.orderStatus, "CANCELLED");
  assert.deepEqual(client.releaseCalls.map((call) => call.holdId), ["hold-tvl-1"]);
});

test("captured payment continues to ticketing after the authorization deadline", async () => {
  const entry = matchingEntry(["tvl-1"]);
  const traveler = entry.fulfillment.travelers[0]!;
  entry.recordBookingSaga("saga-1");
  entry.recordReservation(traveler.travelerRef, "CONFIRMED", "hold-tvl-1");
  entry.recordPayment("pi-fulfillment", "CAPTURED");
  const repository = new SnapshotRepository(entry);
  const client = new DurableFulfillmentClient(["tvl-1"]);
  client.seedReservation(entry, traveler, "CONFIRMED", "hold-tvl-1");
  await client.createPayment(entry);
  await client.capturePayment(entry);
  const service = new WaitlistApplicationService(repository, new InMemoryEventPublisher(), undefined, undefined, undefined,
    () => new Date("2026-10-19T00:00:00.000Z"), undefined, undefined, client);

  await service.advanceNextWork();

  assert.equal(client.entitlementEffects, 1);
  assert.equal(client.orderCancelCalls.length, 0);
});

test("completed local effects stay matching until authoritative order confirmation", async () => {
  const entry = matchingEntry(["tvl-1"]);
  const repository = new SnapshotRepository(entry);
  const publisher = new InMemoryEventPublisher();
  const client = new DurableFulfillmentClient(["tvl-1"], false);
  let now = NOW;

  for (let step = 0; step < 20 && client.entitlementEffects === 0; step++) {
    const service = new WaitlistApplicationService(repository, publisher, undefined, undefined, undefined, () => now, undefined, undefined, client);
    await service.advanceNextWork();
    now = new Date(now.getTime() + 1_001);
  }

  assert.equal(client.entitlementEffects, 1);
  assert.equal(repository.current().status, "MATCHING");
  assert.equal(publisher.findByEventType("WaitlistFulfilled").length, 0);

  client.orderStatus = "CONFIRMED";
  const service = new WaitlistApplicationService(repository, publisher, undefined, undefined, undefined, () => now, undefined, undefined, client);
  await service.advanceNextWork();
  await service.handleJourneyOrderConfirmed(entry.journeyOrderRef!);

  assert.equal(repository.current().status, "FULFILLED");
  assert.equal(publisher.findByEventType("WaitlistFulfilled").length, 1);
});

test("a payment captured concurrently with authorization cleanup resumes ticketing without releasing capacity", async () => {
  const entry = matchingEntry(["tvl-1"]);
  const traveler = entry.fulfillment.travelers[0]!;
  entry.recordBookingSaga("saga-1");
  entry.recordReservation(traveler.travelerRef, "CONFIRMED", "hold-tvl-1");
  const repository = new SnapshotRepository(entry);
  const client = new DurableFulfillmentClient(["tvl-1"], false);
  client.seedReservation(entry, traveler, "CONFIRMED", "hold-tvl-1");
  await client.createPayment(entry);
  entry.recordPayment("pi-fulfillment", "CREATED");
  await repository.save(entry);
  client.captureDuringCancellation = true;
  let now = new Date("2026-10-19T00:00:00.000Z");
  const service = new WaitlistApplicationService(repository, new InMemoryEventPublisher(), undefined, undefined, undefined,
    () => now, undefined, undefined, client);

  await service.advanceNextWork();
  client.sagaStatusOverride = "TICKETING";
  now = new Date(now.getTime() + 1_001);
  await service.advanceNextWork();

  assert.equal(repository.current().status, "MATCHING");
  assert.equal(repository.current().fulfillment?.paymentStatus, "CAPTURED");
  assert.equal(client.paymentCancelCalls.length, 1);
  assert.equal(client.releaseCalls.length, 0);
  assert.equal(client.orderCancelCalls.length, 0);
  assert.equal(client.entitlementEffects, 1);
});
