import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { deliveryPolicyDecision, expiryDecision, notificationTtl, supersedingSignatures } from "./delivery-policy.js";
import type { NotificationTaskSnapshot } from "./domain.js";

function task(overrides: Partial<NotificationTaskSnapshot> = {}): NotificationTaskSnapshot {
  return {
    notificationTaskId: "nt-policy-1",
    status: "Planned",
    triggerEventId: "evt-policy-1",
    triggerEventType: "JourneyOrderCreated",
    correlationId: "corr-policy-1",
    recipientRef: "acc-policy-1",
    templateCode: "order_created",
    channel: "EMAIL",
    intent: "ORDER_CREATED",
    transactionRequired: true,
    variables: { orderId: "ord-policy-1" },
    scheduledAt: new Date("2026-09-25T00:00:00Z"),
    triggerBusinessRef: "JourneyOrderCreated:ord-policy-1",
    receipts: [],
    ...overrides,
  };
}

describe("notification delivery policy", () => {
  it("uses shorter TTLs for actionable and state notifications than financial facts", () => {
    assert.equal(notificationTtl(task({ templateCode: "PAYMENT_REMINDER" })).milliseconds, 30 * 60_000);
    assert.equal(notificationTtl(task()).milliseconds, 6 * 60 * 60_000);
    assert.equal(notificationTtl(task({ templateCode: "payment_captured" })).milliseconds, 72 * 60 * 60_000);
  });

  it("expires stale state notifications but retains fresh and financial notifications", () => {
    const now = new Date("2026-09-25T08:00:00Z");
    assert.deepEqual(expiryDecision(task(), now), { action: "expire", reasonCode: "ttl_order_state" });
    assert.equal(expiryDecision(task({ scheduledAt: new Date("2026-09-25T07:00:00Z") }), now), undefined);
    assert.equal(expiryDecision(task({ templateCode: "payment_captured" }), now), undefined);
  });

  it("derives only exact, bounded superseding signatures", () => {
    assert.deepEqual(supersedingSignatures(task()), [
      { triggerBusinessRef: "JourneyOrderConfirmed:ord-policy-1", templateCode: "ORDER_CONFIRMED" },
      { triggerBusinessRef: "JourneyOrderCancelled:ord-policy-1", templateCode: "order_cancelled" },
    ]);
    assert.deepEqual(supersedingSignatures(task({ templateCode: "ticket_issued" })), []);
  });

  it("selects supersession before expiry without exporting business identifiers", async () => {
    const calls: readonly unknown[][] = [];
    const mutableCalls = calls as unknown[][];
    const client = { query: async (_sql: string, params: unknown[]) => {
      mutableCalls.push(params);
      return { rowCount: 1, rows: [{ "?column?": 1 }] };
    } };
    assert.deepEqual(await deliveryPolicyDecision(client as never, task(), new Date("2026-09-25T08:00:00Z")), {
      action: "supersede", reasonCode: "newer_terminal_state",
    });
    assert.equal(mutableCalls.length, 1);
    assert.equal(mutableCalls[0][0], "acc-policy-1");
    assert.match(String(mutableCalls[0][2]), /JourneyOrderCancelled:ord-policy-1/u);
  });
});
