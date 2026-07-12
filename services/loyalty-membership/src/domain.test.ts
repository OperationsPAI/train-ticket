import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { DomainError, Member, PointsCalculator, PointsLedger, RedemptionPolicy, Tier, TierEvaluator, pointsForTicketPrice } from "./domain.js";

function expectDomainError(fn: () => unknown, code: string): void {
  assert.throws(fn, (error: unknown) => error instanceof DomainError && error.code === code);
}

const confirmedOrder = {
  orderId: "ord-001",
  accountId: "acct-001",
  ticketPrice: { currency: "CNY", minorUnits: 50_000 },
  sourceEventId: "evt-order-001",
  confirmedAt: new Date("2026-07-10T10:00:00.000Z"),
  correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
};

describe("points calculation", () => {
  it("calculates Gold business-class weekend earning for 500 CNY as 1800 points", () => {
    assert.equal(PointsCalculator.calculate({
      fare: { currency: "CNY", minorUnits: 50_000 },
      seatClass: "BUSINESS_CLASS",
      travelDate: new Date("2026-07-11T10:00:00.000Z"),
      memberTier: "GOLD",
    }), 1_800);
  });

  it("keeps the backward-compatible helper as one point per CNY", () => {
    assert.equal(pointsForTicketPrice({ currency: "CNY", minorUnits: 0 }), 0);
    assert.equal(pointsForTicketPrice({ currency: "CNY", minorUnits: 100 }), 1);
    assert.equal(pointsForTicketPrice({ currency: "CNY", minorUnits: 200 }), 2);
  });

  it("rejects unsupported currencies", () => {
    expectDomainError(() => pointsForTicketPrice({ currency: "USD", minorUnits: 10_000 }), "UNSUPPORTED_CURRENCY");
  });
});

describe("Tier value object", () => {
  it("maps annual qualifying points and trips to tiers", () => {
    assert.equal(Tier.fromTierPoints(0).name, "SILVER");
    assert.equal(TierEvaluator.evaluate(3_000, 0), "GOLD");
    assert.equal(TierEvaluator.evaluate(0, 30), "PLATINUM");
    assert.equal(TierEvaluator.evaluate(25_000, 0), "DIAMOND");
  });
});

describe("PointsLedger", () => {
  it("accrues immutable points lots and rejects duplicate source facts", () => {
    const ledger = PointsLedger.empty();
    const sourceFactRef = { stream: "events:payment", eventType: "PAYMENT_CAPTURED", eventId: "evt-1", aggregateId: "ord-1", occurredAt: new Date("2026-07-10T10:00:00.000Z") };
    const result = ledger.accrue({
      memberId: "mem-1",
      points: 10,
      sourceFactRef,
      businessReason: { reasonType: "ORDER_ACCRUAL", reasonCode: "TICKET_PURCHASE_CAPTURED", referenceType: "JOURNEY_ORDER", referenceId: "ord-1" },
      idempotencyKey: "mem-1:evt-1",
      occurredAt: sourceFactRef.occurredAt,
    });

    assert.equal(result.ledger.balance, 10);
    assert.equal(result.lot.remainingPoints, 10);
    expectDomainError(() => result.ledger.accrue({ memberId: "mem-1", points: 10, sourceFactRef, businessReason: { reasonType: "ORDER_ACCRUAL", reasonCode: "TICKET_PURCHASE_CAPTURED", referenceType: "JOURNEY_ORDER", referenceId: "ord-1" }, idempotencyKey: "mem-1:evt-1", occurredAt: sourceFactRef.occurredAt }), "POINTS_ALREADY_ACCRUED");
  });
});

describe("redemption policy", () => {
  it("redeems 1000 points for a 10 CNY discount on a 200 CNY fare", () => {
    assert.deepEqual(RedemptionPolicy.standard.apply({ memberId: "mem-1", orderId: "ord-1", pointsToRedeem: 1_000, fareAmountMinor: 20_000 }, 2_000), {
      pointsDeducted: 1_000,
      discountAmountMinor: 1_000,
      remainingBalance: 1_000,
    });
  });

  it("caps redemption at 50% of fare", () => {
    assert.deepEqual(RedemptionPolicy.standard.apply({ memberId: "mem-1", orderId: "ord-1", pointsToRedeem: 20_000, fareAmountMinor: 20_000 }, 20_000), {
      pointsDeducted: 10_000,
      discountAmountMinor: 10_000,
      remainingBalance: 10_000,
    });
  });
});

describe("Member aggregate", () => {
  it("accrues points from confirmed ticket price and emits PointsEarned", () => {
    const member = Member.enroll({ accountId: "acct-001", memberId: "mem-001", now: new Date("2026-07-10T09:00:00.000Z") });

    const { member: updated, events } = member.accrueFromConfirmedOrder(confirmedOrder, new Date("2026-07-10T10:01:00.000Z"));

    assert.equal(updated.redeemablePoints, 500);
    assert.equal(updated.tier, "SILVER");
    const earned = events[0];
    assert.equal(earned.type, "PointsEarned");
    if (earned.type !== "PointsEarned") throw new Error("expected PointsEarned");
    assert.equal(earned.points, 500);
    assert.equal(earned.sourceFactRef.eventType, "PAYMENT_CAPTURED");
    assert.equal(earned.sourceFactRef.aggregateId, "ord-001");
  });

  it("upgrades immediately to PLATINUM when annual points reach 10000", () => {
    const member = Member.enroll({ accountId: "acct-002", memberId: "mem-002" });
    const { member: updated, events } = member.accrueFromConfirmedOrder({ ...confirmedOrder, accountId: "acct-002", sourceEventId: "evt-large", ticketPrice: { currency: "CNY", minorUnits: 1_000_000 } });

    assert.equal(updated.tier, "PLATINUM");
    assert.ok(events.some((event) => event.type === "MemberTierUpgraded"));
  });

  it("downgrades to SILVER at year end when not re-qualified", () => {
    const platinum = Member.fromSnapshot({ ...Member.enroll({ accountId: "acct-003", memberId: "mem-003", now: new Date("2026-01-01T00:00:00.000Z") }).toSnapshot(), tier: "PLATINUM", membershipYears: [{ year: 2026, qualifyingPoints: 2_999, tripCount: 11, currentTier: "PLATINUM" }] });

    const { member: evaluated, events } = platinum.evaluateTierAtYearEnd(2026, new Date("2027-04-01T00:00:00.000Z"));

    assert.equal(evaluated.tier, "SILVER");
    assert.ok(events.some((event) => event.type === "MemberTierDowngraded"));
  });

  it("redeems ticket points and reduces balance", () => {
    const member = Member.enroll({ accountId: "acct-004", memberId: "mem-004" });
    const { member: accrued } = member.accrueFromConfirmedOrder({ ...confirmedOrder, accountId: "acct-004", sourceEventId: "evt-redeem", ticketPrice: { currency: "CNY", minorUnits: 100_000 } });

    const { member: redeemed, result } = accrued.redeemForTicket({ memberId: accrued.id, orderId: "ord-ticket", pointsToRedeem: 1_000, fareAmountMinor: 20_000, redemptionId: "red-001" });

    assert.equal(result.discountAmountMinor, 1_000);
    assert.equal(redeemed.redeemablePoints, 0);
    expectDomainError(() => redeemed.redeem({ memberId: redeemed.id, points: 5 }), "INSUFFICIENT_POINTS");
  });

  it("expires points earned 25 months ago in monthly batch", () => {
    const member = Member.enroll({ accountId: "acct-005", memberId: "mem-005" });
    const { member: accrued } = member.accrueFromConfirmedOrder({ ...confirmedOrder, accountId: "acct-005", sourceEventId: "evt-expire", confirmedAt: new Date("2024-01-01T00:00:00.000Z"), ticketPrice: { currency: "CNY", minorUnits: 100_000 } });

    const { member: expired, events } = accrued.expirePoints(new Date("2026-02-01T00:00:00.000Z"));

    assert.equal(expired.redeemablePoints, 0);
    assert.equal(events[0]?.type, "PointsExpired");
  });

  it("keeps snapshots immutable", () => {
    const snapshot = Member.enroll({ accountId: "acct-006", memberId: "mem-006" }).toSnapshot();
    assert.throws(() => { (snapshot as { tier: string }).tier = "GOLD"; }, TypeError);
  });
});
