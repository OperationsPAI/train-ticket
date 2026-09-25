import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { OutboxAppender, withTransaction } from "@trainticket/ts-kit";
import { Pool } from "pg";
import { WaitlistApplicationService } from "../src/application.js";
import { WaitlistEntry, type FulfillmentTraveler } from "../src/domain.js";
import type { EntitlementFacts, OrderFacts, PaymentFacts, ReservationFacts, SagaFacts, WaitlistFulfillmentClient } from "../src/fulfillment.js";
import { PostgresWaitlistRepository, TransactionalOutboxPublisher } from "../src/storage.js";

const NOW = new Date("2026-09-25T00:00:00.000Z");
const AMOUNT = { currency: "CNY", minorUnits: 10_750 } as const;

class DurableEffects implements WaitlistFulfillmentClient {
  public orderStatus = "CREATED";
  public reservationEffects = 0;
  public paymentCreateEffects = 0;
  public paymentCaptureEffects = 0;
  public entitlementEffects = 0;
  private reservation?: ReservationFacts;
  private payment?: PaymentFacts;
  private entitlement?: EntitlementFacts;

  async getOrder(orderId: string): Promise<OrderFacts> {
    return { orderId, accountId: "acc-live", offerId: "off-live", status: this.orderStatus, riskClear: true,
      travelerRefs: ["tvl-live"], segmentRefs: ["seg-live"], amount: AMOUNT };
  }
  async findSaga(orderId: string): Promise<SagaFacts> {
    return { sagaId: "saga-live", orderId, status: this.payment?.status === "CAPTURED" ? "TICKETING" : this.reservation ? "WAITING_PAYMENT" : "RESERVING" };
  }
  async requestReservation(entry: WaitlistEntry, traveler: FulfillmentTraveler): Promise<ReservationFacts> {
    if (!this.reservation) {
      this.reservationEffects++;
      this.reservation = { segmentBookingId: traveler.segmentBookingId, orderId: entry.journeyOrderRef!, travelerRef: traveler.travelerRef,
        segmentRef: entry.segmentRef, status: "CONFIRMED", capacityHoldId: "hold-live" };
    }
    return this.reservation;
  }
  async getReservation() { return this.reservation; }
  async createPayment(entry: WaitlistEntry): Promise<PaymentFacts> {
    if (!this.payment) {
      this.paymentCreateEffects++;
      this.payment = { paymentIntentId: "pi-live", businessRef: entry.journeyOrderRef!, payerRef: entry.accountId, status: "CREATED", amount: AMOUNT };
    }
    return this.payment;
  }
  async findPayment(orderId: string) { return this.payment?.businessRef === orderId ? this.payment : undefined; }
  async getPayment() { return this.payment!; }
  async capturePayment(entry: WaitlistEntry): Promise<PaymentFacts> {
    if (this.payment?.status !== "CAPTURED") {
      this.paymentCaptureEffects++;
      this.payment = { paymentIntentId: "pi-live", businessRef: entry.journeyOrderRef!, payerRef: entry.accountId, status: "CAPTURED", amount: AMOUNT };
    }
    return this.payment;
  }
  async cancelPayment() { return this.payment; }
  async releaseCapacity() {}
  async cancelOrder() {}
  async issueEntitlement(entry: WaitlistEntry, traveler: FulfillmentTraveler): Promise<EntitlementFacts> {
    if (!this.entitlement) {
      this.entitlementEffects++;
      this.entitlement = { entitlementId: "ent-live", segmentBookingId: traveler.segmentBookingId, orderId: entry.journeyOrderRef!, status: "ISSUED" };
    }
    return this.entitlement;
  }
}

class SimulatedCrash extends Error {}

test("PostgreSQL rollback recovery keeps reservation payment and ticket effects exactly once", {
  skip: !process.env.TEST_DATABASE_URL,
}, async () => {
  const schema = `waitlist_fulfillment_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 1 });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 4, options: `-c search_path=${schema}` });
  const effects = new DurableEffects();
  try {
    await pool.query(await readFile("migrations/001_waitlist.sql", "utf8"));
    await pool.query(await readFile("migrations/009_fulfillment_recovery_index.sql", "utf8"));
    const entry = WaitlistEntry.create({ entryId: "wlr-live", accountId: "acc-live", travelerRefs: ["tvl-live"], segmentRef: "seg-live",
      scheduledServiceRef: "svc-live", departureDate: "2026-10-20", seatClass: "SECOND_CLASS", interval: { fromSeq: 1, toSeq: 4 },
      stopSequenceVersion: "sha256:route-live", priority: {}, deadline: "2026-10-19T00:00:00.000Z" });
    entry.beginPromotion([{ capacityReleaseRef: "evt-live", slots: 1 }]);
    entry.recordFareQuote("fq-live");
    entry.recordOrderAmount(AMOUNT);
    entry.offer("off-live", 1, "fq-live", undefined, NOW, new Date("2026-09-25T00:15:00.000Z"));
    entry.linkJourneyOrder(NOW, "ord-live", AMOUNT);
    await new PostgresWaitlistRepository(pool).add(entry);

    const advance = async (rollback = false) => {
      try {
        return await withTransaction(pool, async (client) => {
          const result = await new WaitlistApplicationService(new PostgresWaitlistRepository(client),
            new TransactionalOutboxPublisher(new OutboxAppender(client)), undefined, undefined, undefined, () => NOW,
            undefined, undefined, effects).advanceNextWork();
          if (rollback) throw new SimulatedCrash();
          return result;
        });
      } catch (error) {
        if (rollback && error instanceof SimulatedCrash) return undefined;
        throw error;
      }
    };

    await advance();
    for (const count of [() => effects.reservationEffects, () => effects.paymentCreateEffects, () => effects.paymentCaptureEffects, () => effects.entitlementEffects]) {
      const before = count();
      await advance(true);
      assert.equal(count(), before + 1);
      await advance();
      assert.equal(count(), before + 1);
    }
    effects.orderStatus = "CONFIRMED";
    await advance();

    assert.equal((await new PostgresWaitlistRepository(pool).get(entry.entryId))?.status, "FULFILLED");
    assert.equal(effects.reservationEffects, 1);
    assert.equal(effects.paymentCreateEffects, 1);
    assert.equal(effects.paymentCaptureEffects, 1);
    assert.equal(effects.entitlementEffects, 1);
    assert.equal((await pool.query("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='WaitlistFulfilled'")).rows[0].count, "1");
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});
