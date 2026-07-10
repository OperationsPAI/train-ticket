import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { DomainError, Member, PointsLedger, Tier, pointsForTicketPrice } from "./domain.js";

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

describe("Tier value object", () => {
  it("maps rolling tier points to the baseline tiers", () => {
    assert.equal(Tier.fromTierPoints(0).name, "BASIC");
    assert.equal(Tier.fromTierPoints(5_000).name, "SILVER");
    assert.equal(Tier.fromTierPoints(20_000).name, "GOLD");
    assert.equal(Tier.fromTierPoints(50_000).name, "PLATINUM");
  });
});

describe("PointsLedger", () => {
  it("accrues immutable points lots and rejects duplicate source facts", () => {
    const ledger = PointsLedger.empty();
    const sourceFactRef = {
      stream: "events:journey-order",
      eventType: "JOURNEY_ORDER_CONFIRMED",
      eventId: "evt-1",
      aggregateId: "ord-1",
      occurredAt: new Date("2026-07-10T10:00:00.000Z"),
    };
    const result = ledger.accrue({
      memberId: "mem-1",
      points: 10,
      sourceFactRef,
      businessReason: { reasonType: "ORDER_ACCRUAL", reasonCode: "JOURNEY_ORDER_CONFIRMED", referenceType: "JOURNEY_ORDER", referenceId: "ord-1" },
      idempotencyKey: "mem-1:evt-1",
      occurredAt: sourceFactRef.occurredAt,
    });

    assert.equal(result.ledger.balance, 10);
    assert.equal(result.lot.remainingPoints, 10);
    expectDomainError(
      () => result.ledger.accrue({
        memberId: "mem-1",
        points: 10,
        sourceFactRef,
        businessReason: { reasonType: "ORDER_ACCRUAL", reasonCode: "JOURNEY_ORDER_CONFIRMED", referenceType: "JOURNEY_ORDER", referenceId: "ord-1" },
        idempotencyKey: "mem-1:evt-1",
        occurredAt: sourceFactRef.occurredAt,
      }),
      "POINTS_ALREADY_ACCRUED",
    );
  });
});

describe("Member aggregate", () => {
  it("accrues one point per 100 CNY from confirmed ticket price", () => {
    const member = Member.enroll({ accountId: "acct-001", memberId: "mem-001", now: new Date("2026-07-10T09:00:00.000Z") });

    const { member: updated, events } = member.accrueFromConfirmedOrder(confirmedOrder, new Date("2026-07-10T10:01:00.000Z"));

    assert.equal(updated.redeemablePoints, 5);
    assert.equal(updated.tier, "BASIC");
    assert.equal(events[0].type, "PointsAccrued");
    assert.equal(events[0].points, 5);
    assert.equal(events[0].sourceFactRef.eventType, "JOURNEY_ORDER_CONFIRMED");
    assert.equal(events[0].sourceFactRef.aggregateId, "ord-001");
  });

  it("upgrades tier immediately when accrual crosses a threshold", () => {
    let member = Member.enroll({ accountId: "acct-002", memberId: "mem-002" });
    ({ member } = member.accrueFromConfirmedOrder({ ...confirmedOrder, accountId: "acct-002", sourceEventId: "evt-large", ticketPrice: { currency: "CNY", minorUnits: 50_000_000 } }));

    assert.equal(member.tier, "SILVER");
  });

  it("redeems available points without making the balance negative", () => {
    const member = Member.enroll({ accountId: "acct-003", memberId: "mem-003" });
    const { member: accrued } = member.accrueFromConfirmedOrder({ ...confirmedOrder, accountId: "acct-003", sourceEventId: "evt-redeem", ticketPrice: { currency: "CNY", minorUnits: 100_000 } });

    const { member: redeemed, event } = accrued.redeem({ memberId: accrued.id, points: 6, redemptionId: "red-001" });

    assert.equal(redeemed.redeemablePoints, 4);
    assert.equal(event.type, "PointsRedeemed");
    assert.equal(event.balanceAfter, 4);
    expectDomainError(() => redeemed.redeem({ memberId: redeemed.id, points: 5 }), "INSUFFICIENT_POINTS");
  });

  it("keeps snapshots immutable", () => {
    const member = Member.enroll({ accountId: "acct-004", memberId: "mem-004" });
    const snapshot = member.toSnapshot();
    assert.throws(() => {
      (snapshot as { tier: string }).tier = "GOLD";
    }, TypeError);
  });
});

describe("points calculation", () => {
  it("rounds down to one point per 100 CNY", () => {
    assert.equal(pointsForTicketPrice({ currency: "CNY", minorUnits: 9_999 }), 0);
    assert.equal(pointsForTicketPrice({ currency: "CNY", minorUnits: 10_000 }), 1);
    assert.equal(pointsForTicketPrice({ currency: "CNY", minorUnits: 19_999 }), 1);
  });

  it("rejects unsupported currencies", () => {
    expectDomainError(() => pointsForTicketPrice({ currency: "USD", minorUnits: 10_000 }), "UNSUPPORTED_CURRENCY");
  });
});
