import assert from "node:assert/strict";
import { describe, it } from "node:test";

import type { Pool } from "pg";

import {
  claimDeliveries,
  startDeliveryWorker,
  type DeliveryClaim,
  type DeliveryWorkerOperations,
} from "./adapters/storage/delivery-queue.js";

type QueryCall = Readonly<{ sql: string; params: unknown[] }>;

class FakeClaimPool {
  public readonly calls: QueryCall[] = [];

  constructor(
    private readonly rows: readonly Record<string, unknown>[],
    private readonly updated = rows.length,
  ) {}

  async connect() {
    return {
      query: async (sql: string, params: unknown[] = []) => {
        this.calls.push({ sql, params });
        if (sql === "BEGIN" || sql === "COMMIT" || sql === "ROLLBACK") return { rows: [], rowCount: 0 };
        if (sql.includes("SELECT * FROM notification_delivery_queue")) {
          return { rows: this.rows.slice(0, params[0] as number), rowCount: this.rows.length };
        }
        if (sql.includes("UPDATE notification_delivery_queue AS queue")) {
          return { rows: [], rowCount: this.updated };
        }
        if (sql.includes("INSERT INTO notification_delivery_attempts")) {
          return { rows: [], rowCount: this.rows.length };
        }
        if (sql.includes("INSERT INTO notification_delivery_evidence")) {
          return { rows: [], rowCount: this.rows.length };
        }
        throw new Error(`Unexpected query: ${sql}`);
      },
      release: () => {},
    };
  }
}

function queueRow(index: number, channelIndex = 0): Record<string, unknown> {
  return {
    task_id: `task-${index}`,
    channels: [{ channel: "EMAIL", variables: {} }, { channel: "SMS", variables: {} }],
    channel_index: channelIndex,
    state: "Ready",
    claim_token: null,
    attempt_number: index,
    message_id: `<task-${index}@notification.test>`,
  };
}

async function until(predicate: () => boolean, timeoutMs = 1_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    assert.ok(Date.now() < deadline, "condition did not become true");
    await new Promise(resolve => setTimeout(resolve, 1));
  }
}

describe("notification delivery queue throughput", () => {
  it("claims a batch with one locked selection and set-based writes", async () => {
    const pool = new FakeClaimPool([queueRow(1), queueRow(2, 1), queueRow(3)]);

    const claims = await claimDeliveries(pool as never as Pool, 3);

    assert.deepEqual(claims.map(claim => claim.taskId), ["task-1", "task-2", "task-3"]);
    assert.equal(new Set(claims.map(claim => claim.token)).size, 3);
    assert.ok(claims.every(claim => /^[0-9a-f-]{36}$/u.test(claim.token)));
    const select = pool.calls.filter(call => call.sql.includes("SELECT * FROM notification_delivery_queue"));
    assert.equal(select.length, 1);
    assert.match(select[0].sql, /LIMIT \$1 FOR UPDATE SKIP LOCKED/u);
    assert.deepEqual(select[0].params, [3]);
    assert.equal(pool.calls.filter(call => call.sql.includes("UPDATE notification_delivery_queue AS queue")).length, 1);
    assert.equal(pool.calls.filter(call => call.sql.includes("INSERT INTO notification_delivery_attempts")).length, 1);
    assert.equal(pool.calls.filter(call => call.sql.includes("INSERT INTO notification_delivery_evidence")).length, 1);
    const payload = JSON.parse(pool.calls.find(call => call.sql.includes("UPDATE notification_delivery_queue AS queue"))!.params[0] as string) as Array<{ attempt_number: number; channel: string }>;
    assert.deepEqual(payload.map(row => [row.attempt_number, row.channel]), [[2, "EMAIL"], [3, "SMS"], [4, "EMAIL"]]);
    assert.equal(pool.calls.at(-1)?.sql, "COMMIT");
  });

  it("rolls back the whole batch if a locked row was not claimed", async () => {
    const pool = new FakeClaimPool([queueRow(1), queueRow(2)], 1);

    await assert.rejects(() => claimDeliveries(pool as never as Pool, 2), /Claimed 1 of 2/u);

    assert.equal(pool.calls.some(call => call.sql === "ROLLBACK"), true);
    assert.equal(pool.calls.some(call => call.sql.includes("INSERT INTO notification_delivery_attempts")), false);
  });

  it("fills all send slots from a batch and never exceeds configured concurrency", async () => {
    const queued: DeliveryClaim[] = Array.from({ length: 9 }, (_, index) => ({ taskId: `task-${index}`, token: `token-${index}` }));
    const claimSizes: number[] = [];
    const active = new Map<string, () => void>();
    let activeCount = 0;
    let maxActive = 0;
    let started = 0;
    const operations: DeliveryWorkerOperations = {
      claim: async limit => {
        claimSizes.push(limit);
        return queued.splice(0, limit);
      },
      send: claim => new Promise<void>(resolve => {
        started += 1;
        activeCount += 1;
        maxActive = Math.max(maxActive, activeCount);
        active.set(claim.taskId, () => {
          active.delete(claim.taskId);
          activeCount -= 1;
          resolve();
        });
      }),
      recover: async () => {},
    };
    const worker = startDeliveryWorker({} as Pool, {} as never, 4, operations);
    try {
      await until(() => started === 4);
      assert.equal(activeCount, 4);
      assert.deepEqual(claimSizes, [4]);

      active.values().next().value!();
      await until(() => started === 5);
      assert.equal(activeCount, 4);
      assert.equal(maxActive, 4);
      assert.deepEqual(claimSizes, [4, 1]);

      while (started < 9) {
        const before = started;
        active.values().next().value!();
        await until(() => started === before + 1);
      }
      for (const release of [...active.values()]) release();
      await until(() => activeCount === 0);
    } finally {
      for (const release of [...active.values()]) release();
      await worker.stop();
    }
    assert.equal(maxActive, 4);
    assert.equal(started, 9);
  });
});
