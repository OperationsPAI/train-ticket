import type { Pool } from "pg";

import type { NotificationQueueObservation } from "./metrics.js";

/**
 * Current durable delivery queue depth, one sample per state and channel.
 *
 * Read from `notification_delivery_queue_state`, which a trigger on the queue
 * table maintains, rather than counting the queue itself: the queue holds every
 * task the service ever accepted, so a count is a scan of tens of millions of
 * rows (measured at 3.7 seconds on the resident database).
 */
export async function readDeliveryQueueDepth(pool: Pool): Promise<NotificationQueueObservation[]> {
  const buckets = await pool.query<{ state: string; channel: string; tasks: string }>(
    "SELECT state, channel, tasks FROM notification_delivery_queue_state ORDER BY state, channel",
  );
  const oldest = await readOldestDue(pool);
  const samples: NotificationQueueObservation[] = [];
  for (const bucket of buckets.rows) {
    const tasks = Number.parseInt(bucket.tasks, 10);
    // Only Ready is a user-delay signal, and only Ready has an index that
    // answers "oldest" without scanning the table. The other states are
    // reported as depth alone, with no age attributed to them.
    const age = bucket.state === "Ready" && oldest.channel === bucket.channel ? oldest.ageSeconds : 0;
    samples.push({
      state: bucket.state,
      channel: bucket.channel,
      tasks: Number.isFinite(tasks) ? tasks : 0,
      oldestAgeSeconds: age,
    });
  }
  return samples;
}

/**
 * Age and channel of the oldest delivery that is due now.
 *
 * `ORDER BY available_at, task_id LIMIT 1` is answered by the partial index
 * `notification_delivery_ready_idx` in about 70 milliseconds; the equivalent
 * quantity over any other state would need a heap scan.
 */
async function readOldestDue(pool: Pool): Promise<{ channel: string; ageSeconds: number }> {
  const result = await pool.query<{ channel: string | null; age_seconds: string | null }>(
    `SELECT queue.channels->queue.channel_index->>'channel' AS channel,
            EXTRACT(EPOCH FROM now()-queue.available_at)::bigint AS age_seconds
       FROM notification_delivery_queue queue
      WHERE queue.state='Ready' AND queue.available_at<=now()
      ORDER BY queue.available_at, queue.task_id
      LIMIT 1`,
  );
  const row = result.rows[0];
  if (!row) return { channel: "", ageSeconds: 0 };
  return { channel: row.channel ?? "unknown", ageSeconds: Math.max(0, Number.parseInt(row.age_seconds ?? "0", 10) || 0) };
}

/**
 * How often the depth snapshot is refreshed.
 *
 * The gauges are observable, so the exporter decides when they are read; this
 * only bounds how stale a sample can be. The two queries together cost about
 * 70 milliseconds, so the interval is chosen for freshness, not for cost.
 */
export function queueDepthRefreshMs(): number {
  const configured = Number.parseInt(process.env.NOTIFICATION_QUEUE_DEPTH_REFRESH_MS ?? "", 10);
  return Number.isFinite(configured) && configured >= 1000 ? configured : 15_000;
}
