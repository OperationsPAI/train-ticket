import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { InMemoryEventPublisher, InMemoryMemberRepository, LoyaltyMembershipApplicationService } from "./application.js";
import { handleJourneyOrderCancelled, handleJourneyOrderConfirmed, handlePaymentCaptured, handlePostSalesApplied } from "./bootstrap.js";
import { createEventEnvelope } from "@trainticket/ts-kit";

describe("journey-order event consumption", () => {
  it("does not accrue points from JourneyOrderConfirmed", async () => {
    const repository = new InMemoryMemberRepository();
    const publisher = new InMemoryEventPublisher();
    const service = new LoyaltyMembershipApplicationService(repository, publisher);
    const envelope = createEventEnvelope({
      eventType: "JourneyOrderConfirmed",
      producer: "journey-order",
      payload: {
        orderId: "ord-msg",
        accountId: "acct-msg",
        monetarySummary: { currency: "CNY", total: { currency: "CNY", minorUnits: 250_000 } },
        confirmedAt: "2026-07-10T10:00:00.000Z",
      },
    });

    await handleJourneyOrderConfirmed(service, envelope);

    const member = await repository.findByAccountId("acct-msg");
    assert.ok(member);
    assert.equal(member.redeemablePoints, 0);
    assert.equal(publisher.findByEventType("PointsEarned").length, 0);
  });

  it("restores ticket redemption points from JourneyOrderCancelled", async () => {
    const repository = new InMemoryMemberRepository();
    const publisher = new InMemoryEventPublisher();
    const service = new LoyaltyMembershipApplicationService(repository, publisher);
    const member = await service.enrollMember("acct-cancel-msg");
    await service.accrueFromPaymentCaptured({ orderId: "ord-cancel-msg", accountId: "acct-cancel-msg", ticketPrice: { currency: "CNY", minorUnits: 100_000 }, sourceEventId: "evt-pay-cancel", confirmedAt: new Date("2026-07-10T10:00:00.000Z"), correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c333" });
    await service.redeemTicketPoints({ memberId: member.member.memberId, orderId: "ord-cancel-msg", pointsToRedeem: 1_000, fareAmountMinor: 20_000, redemptionId: "red-cancel", correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c333" });

    await handleJourneyOrderCancelled(service, createEventEnvelope({ eventType: "JourneyOrderCancelled", producer: "journey-order", payload: { orderId: "ord-cancel-msg", accountId: "acct-cancel-msg", reason: "USER_CANCELLED" } }));

    const restored = await repository.findByAccountId("acct-cancel-msg");
    assert.ok(restored);
    assert.equal(restored.redeemablePoints, 1_000);
    assert.equal(publisher.findByEventType("PointsRestored").length, 1);
  });
});



describe("payment event consumption", () => {
  it("accrues points from PaymentCaptured captured amount", async () => {
    const repository = new InMemoryMemberRepository();
    const publisher = new InMemoryEventPublisher();
    const service = new LoyaltyMembershipApplicationService(repository, publisher);
    const envelope = createEventEnvelope({
      eventType: "PaymentCaptured",
      producer: "payment",
      payload: {
        paymentIntentId: "pi-msg",
        businessRef: "ord-pay-msg",
        accountId: "acct-pay-msg",
        capturedAmount: { currency: "CNY", minorUnits: 50_000 },
        capturedAt: "2026-07-10T10:00:00.000Z",
        seatClass: "BUSINESS_CLASS",
      },
    });

    await handlePaymentCaptured(service, envelope);

    const member = await repository.findByAccountId("acct-pay-msg");
    assert.ok(member);
    assert.equal(member.redeemablePoints, 1000);
    const earned = publisher.findByEventType("PointsEarned")[0];
    const sourceFactRef = earned.payload.sourceFactRef as { eventType: string; aggregateId: string };
    assert.equal(sourceFactRef.eventType, "PAYMENT_CAPTURED");
    assert.equal(sourceFactRef.aggregateId, "ord-pay-msg");
  });
});


describe("post-sales event consumption", () => {
  it("deducts qualifying points for contract-shaped PostSalesApplied refund", async () => {
    const repository = new InMemoryMemberRepository();
    const publisher = new InMemoryEventPublisher();
    const service = new LoyaltyMembershipApplicationService(repository, publisher);
    await service.accrueFromPaymentCaptured({ orderId: "ord-refund-msg", accountId: "acct-refund-msg", ticketPrice: { currency: "CNY", minorUnits: 100_000 }, sourceEventId: "evt-pay-refund", confirmedAt: new Date("2026-07-10T10:00:00.000Z"), correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c444" });

    await handlePostSalesApplied(service, createEventEnvelope({ eventType: "PostSalesApplied", producer: "post-sales", payload: { caseId: "psc-refund", orderId: "ord-refund-msg", resultSummary: { description: "refund applied" } } }));

    const member = await repository.findByAccountId("acct-refund-msg");
    assert.ok(member);
    assert.equal(member.toSnapshot().membershipYears?.find((year) => year.year === 2026)?.qualifyingPoints, 0);
  });
});
