import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { UserAccount } from "./domain.js";
import { toEventEnvelope, type EventEnvelope, type HandlerResult } from "./ports.js";

class FakeSubscriber {
  private readonly seen = new Set<string>();
  handled = 0;

  async deliver(envelope: EventEnvelope, handler: (envelope: EventEnvelope) => HandlerResult): Promise<void> {
    if (this.seen.has(envelope.eventId)) {
      return;
    }
    const result = handler(envelope);
    if (result.ok) {
      this.seen.add(envelope.eventId);
      this.handled += 1;
    }
  }
}

describe("account event ports", () => {
  it("wraps account domain events in the canonical event envelope", () => {
    const { event } = UserAccount.create({ accountId: "acct_123", correlationId: "corr-test" });

    const envelope = toEventEnvelope(event, "corr-test", "cmd-test");

    assert.match(envelope.eventId, /^evt-[0-9a-f-]{36}$/);
    assert.equal(envelope.eventType, "AccountCreated");
    assert.equal(envelope.schemaVersion, 1);
    assert.equal(envelope.producer, "account");
    assert.equal(envelope.causationId, "cmd-test");
    assert.equal(envelope.correlationId, "corr-test");
    assert.match(envelope.occurredAt, /^\d{4}-\d{2}-\d{2}T.*Z$/);
    assert.deepEqual(envelope.payload, { accountId: "acct_123" });
    assert.deepEqual(Object.keys(envelope), [
      "eventId",
      "eventType",
      "schemaVersion",
      "producer",
      "causationId",
      "correlationId",
      "occurredAt",
      "payload",
    ]);
  });

  it("deduplicates consumed events by eventId", async () => {
    const subscriber = new FakeSubscriber();
    const envelope = toEventEnvelope(UserAccount.create({ accountId: "acct_dup" }).event, "corr-dup", "cmd-dup");
    const seen: string[] = [];

    await subscriber.deliver(envelope, (message) => {
      seen.push(message.eventId);
      return { ok: true };
    });
    await subscriber.deliver(envelope, (message) => {
      seen.push(message.eventId);
      return { ok: true };
    });

    assert.deepEqual(seen, [envelope.eventId]);
    assert.equal(subscriber.handled, 1);
  });
});
