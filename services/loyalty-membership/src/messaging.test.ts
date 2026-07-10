import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { InMemoryEventPublisher, InMemoryMemberRepository, LoyaltyMembershipApplicationService } from "./application.js";
import { handleJourneyOrderConfirmed } from "./bootstrap.js";
import { createEventEnvelope } from "@trainticket/ts-kit";

describe("journey-order event consumption", () => {
  it("accrues points from JourneyOrderConfirmed monetary summary", async () => {
    const repository = new InMemoryMemberRepository();
    const publisher = new InMemoryEventPublisher();
    const service = new LoyaltyMembershipApplicationService(repository, publisher);
    const envelope = createEventEnvelope({
      eventType: "JourneyOrderConfirmed",
      producer: "journey-order",
      payload: {
        orderId: "ord-msg",
        accountId: "acct-msg",
        monetarySummary: {
          currency: "CNY",
          total: { currency: "CNY", minorUnits: 250_000 },
        },
        confirmedAt: "2026-07-10T10:00:00.000Z",
      },
    });

    await handleJourneyOrderConfirmed(service, envelope);

    const member = await repository.findByAccountId("acct-msg");
    assert.ok(member);
    assert.equal(member.redeemablePoints, 25);
    const accrued = publisher.findByEventType("PointsAccrued")[0];
    assert.equal(accrued.payload.points, 25);
    const sourceFactRef = accrued.payload.sourceFactRef as { eventType: string; aggregateId: string };
    assert.equal(sourceFactRef.eventType, "JOURNEY_ORDER_CONFIRMED");
    assert.equal(sourceFactRef.aggregateId, "ord-msg");
  });
});
