import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { queueDepthRefreshMs, readDeliveryQueueDepth } from "./queue-depth.js";

type Query = { sql: string; values?: readonly unknown[] };

function poolOver(buckets: Array<{ state: string; channel: string; tasks: string }>, oldest: Array<{ channel: string | null; age_seconds: string | null }>) {
  const queries: Query[] = [];
  return {
    queries,
    pool: {
      query: async (sql: string, values?: readonly unknown[]) => {
        queries.push({ sql, values });
        if (sql.includes("notification_delivery_queue_state")) return { rows: buckets, rowCount: buckets.length };
        return { rows: oldest, rowCount: oldest.length };
      },
    },
  };
}

describe("notification queue depth", () => {
  it("attributes the oldest due age to the Ready channel that owns it and zero elsewhere", async () => {
    const { pool, queries } = poolOver(
      [
        { state: "Claimed", channel: "EMAIL", tasks: "93" },
        { state: "Delivered", channel: "EMAIL", tasks: "11883743" },
        { state: "Ready", channel: "EMAIL", tasks: "2214721" },
      ],
      [{ channel: "EMAIL", age_seconds: "19951" }],
    );

    const samples = await readDeliveryQueueDepth(pool as never);

    assert.deepEqual(samples, [
      { state: "Claimed", channel: "EMAIL", tasks: 93, oldestAgeSeconds: 0 },
      { state: "Delivered", channel: "EMAIL", tasks: 11883743, oldestAgeSeconds: 0 },
      { state: "Ready", channel: "EMAIL", tasks: 2214721, oldestAgeSeconds: 19951 },
    ]);
    // The oldest query has to stay the indexed, ordered, limited form: the
    // unordered min() over the whole queue is a sequential scan.
    const oldestSql = queries.find(query => query.sql.includes("EXTRACT(EPOCH"))!.sql;
    assert.match(oldestSql, /ORDER BY queue\.available_at, queue\.task_id\s+LIMIT 1/u);
    // The depth has to sum the buckets. Reading one row per state and channel
    // was what made every enqueue lock the row a claim needed.
    const depthSql = queries.find(query => query.sql.includes("notification_delivery_queue_state"))!.sql;
    assert.match(depthSql, /sum\(tasks\)/u);
    assert.match(depthSql, /GROUP BY state, channel/u);
  });

  it("reports zero age when nothing is due instead of a missing series", async () => {
    const { pool } = poolOver([{ state: "Ready", channel: "EMAIL", tasks: "5" }], []);

    const samples = await readDeliveryQueueDepth(pool as never);

    assert.deepEqual(samples, [{ state: "Ready", channel: "EMAIL", tasks: 5, oldestAgeSeconds: 0 }]);
  });

  it("falls back to a bounded refresh interval when the environment is unusable", () => {
    const original = process.env.NOTIFICATION_QUEUE_DEPTH_REFRESH_MS;
    try {
      delete process.env.NOTIFICATION_QUEUE_DEPTH_REFRESH_MS;
      assert.equal(queueDepthRefreshMs(), 15_000);
      process.env.NOTIFICATION_QUEUE_DEPTH_REFRESH_MS = "not-a-number";
      assert.equal(queueDepthRefreshMs(), 15_000);
      process.env.NOTIFICATION_QUEUE_DEPTH_REFRESH_MS = "10";
      assert.equal(queueDepthRefreshMs(), 15_000);
      process.env.NOTIFICATION_QUEUE_DEPTH_REFRESH_MS = "30000";
      assert.equal(queueDepthRefreshMs(), 30_000);
    } finally {
      if (original === undefined) delete process.env.NOTIFICATION_QUEUE_DEPTH_REFRESH_MS;
      else process.env.NOTIFICATION_QUEUE_DEPTH_REFRESH_MS = original;
    }
  });
});
