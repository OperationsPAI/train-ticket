import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { Redis } from "ioredis";
import { Pool } from "pg";
import { OutboxRelay } from "./storage.js";

test("concurrent relays publish recorded events without waiting on another publisher's row locks", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_REDIS_URL || !process.env.TEST_OUTBOX_SOURCE,
}, async () => {
  const recorded = JSON.parse(await readFile(process.env.TEST_OUTBOX_SOURCE!, "utf8")) as {
    event_id: string; envelope: Record<string, unknown>;
  }[];
  assert.ok(recorded.length >= 10);
  const suffix = randomUUID().replaceAll("-", "");
  const schema = `outbox_concurrency_${suffix}`;
  const stream = `test:outbox-concurrency:${suffix}`;
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 1 });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 5,
    options: `-c search_path=${schema} -c statement_timeout=2000` });
  const redis = new Redis(process.env.TEST_REDIS_URL!);
  try {
    await pool.query(`CREATE TABLE outbox (
      seq bigserial PRIMARY KEY, event_id text UNIQUE NOT NULL, stream text NOT NULL,
      envelope jsonb NOT NULL, published_at timestamptz)`);
    for (const row of recorded) {
      await pool.query("INSERT INTO outbox(event_id,stream,envelope) VALUES($1,$2,$3)",
        [row.event_id, stream, row.envelope]);
    }
    const locker = await pool.connect();
    try {
      await locker.query("BEGIN");
      await locker.query("SELECT seq FROM outbox WHERE seq=1 FOR UPDATE");
      await assert.rejects(pool.query("UPDATE outbox SET published_at = now() WHERE seq IN (1)"),
        (error: unknown) => (error as { code?: string }).code === "57014");
      const relay = new OutboxRelay(pool, redis, { batchSize: recorded.length });
      assert.equal(await relay.runOnce(), recorded.length);
      const pending = await pool.query("SELECT seq FROM outbox WHERE published_at IS NULL");
      assert.deepEqual(pending.rows, [{ seq: "1" }]);
      await locker.query("ROLLBACK");
    } finally {
      await locker.query("ROLLBACK");
      locker.release();
    }
    const marked = await pool.query("SELECT seq,published_at::text FROM outbox WHERE published_at IS NOT NULL ORDER BY seq");
    const relays = Array.from({ length: 3 }, () => new OutboxRelay(pool, redis, { batchSize: recorded.length }));
    await Promise.all(relays.map((relay) => relay.runOnce()));
    assert.equal((await pool.query("SELECT count(*) FROM outbox WHERE published_at IS NULL")).rows[0].count, "0");
    assert.deepEqual((await pool.query("SELECT seq,published_at::text FROM outbox WHERE seq>1 ORDER BY seq")).rows, marked.rows);
    const deliveries = await redis.xrange(stream, "-", "+");
    const actual = new Map(deliveries.map(([, fields]) => {
      const event = JSON.parse(fields[1]);
      return [event.eventId, event];
    }));
    assert.equal(actual.size, recorded.length);
    assert.equal(deliveries.length, recorded.length, "concurrent relays must not duplicate an outbox row");
    for (const row of recorded) assert.deepEqual(actual.get(row.event_id), row.envelope);
    const length = await redis.xlen(stream);
    assert.equal(await relays[0].runOnce(), 0);
    assert.equal(await redis.xlen(stream), length);
  } finally {
    await redis.del(stream);
    await redis.quit();
    await pool.end();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});
