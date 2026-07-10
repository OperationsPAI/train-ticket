import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createApp, InMemoryEventPublisher, InMemoryMemberRepository, LoyaltyMembershipApplicationService } from "./index.js";

describe("loyalty membership HTTP API", () => {
  it("serves health and readiness endpoints", async () => {
    const app = createApp();

    const health = await app.inject("/health");
    assert.equal(health.statusCode, 200);
    assert.equal(health.json().status, "ok");
    assert.equal(health.json().service.serviceId, "loyalty-membership");

    const ready = await app.inject("/readyz");
    assert.equal(ready.statusCode, 200);
    assert.deepEqual(ready.json(), { status: "ok", probe: "ready" });
  });

  it("fetches and redeems a member", async () => {
    const repository = new InMemoryMemberRepository();
    const publisher = new InMemoryEventPublisher();
    const service = new LoyaltyMembershipApplicationService(repository, publisher);
    const app = createApp({ repository, publisher });
    const member = await service.accrueFromJourneyOrderConfirmed({
      orderId: "ord-http",
      accountId: "acct-http",
      ticketPrice: { currency: "CNY", minorUnits: 100_000 },
      sourceEventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c021",
      confirmedAt: new Date("2026-07-10T10:00:00.000Z"),
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    });

    const get = await app.inject(`/members/${member.memberId}`);
    assert.equal(get.statusCode, 200);
    assert.equal(get.json().redeemablePoints, 10);

    const redeem = await app.inject({
      method: "POST",
      url: `/members/${member.memberId}/redeem`,
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-8284-5c26e8b0c001" },
      payload: { points: 4, redemptionId: "red-http" },
    });
    assert.equal(redeem.statusCode, 200);
    assert.equal(redeem.json().balanceAfter, 6);
    assert.equal(publisher.findByEventType("PointsRedeemed").length, 1);
  });
});
