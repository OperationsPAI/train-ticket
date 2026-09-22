import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import { Pool } from "pg";
import { HandlerError, type EventEnvelope } from "@trainticket/ts-kit";
import { createUpstreamEventHandler, migrationsDirectory } from "./adapters/storage/runtime.js";

test("concurrent projection batches converge and lock timeouts remain retryable", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_ITINERARY_EVENTS_FILE,
}, async () => {
  const events = JSON.parse(await readFile(process.env.TEST_ITINERARY_EVENTS_FILE!, "utf8")) as EventEnvelope[];
  assert.ok(events.length >= 2);
  assert.ok(events.every(event => event.eventType === "ItineraryProposed" && event.producer === "trip-planning"));
  const schema = "offer_projection_" + randomUUID().replaceAll("-", "");
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 1 });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 8, options: `-c search_path=${schema} -c lock_timeout=100ms` });
  try {
    for (const migration of (await readdir(migrationsDirectory())).filter(name => name.endsWith(".sql")).sort()) {
      await pool.query(await readFile(`${migrationsDirectory()}/${migration}`, "utf8"));
    }
    const handler = createUpstreamEventHandler(pool);
    const blocker = await pool.connect();
    try {
      await blocker.query("BEGIN");
      await blocker.query("LOCK TABLE offer_upstream_itineraries IN ACCESS EXCLUSIVE MODE");
      await assert.rejects(handler.handleBatch!(events), error => {
        assert.ok(error instanceof HandlerError);
        assert.equal(error.kind, "transient");
        assert.ok(error.message.includes("55P03"));
        return true;
      });
      assert.equal((await pool.query("SELECT count(*) FROM processed_events")).rows[0].count, "0");
    } finally {
      await blocker.query("ROLLBACK");
      blocker.release();
    }
    const results = await Promise.all(Array.from({ length: 16 }, (_, index) => handler.handleBatch!(index % 2 ? [...events].reverse() : events)));
    assert.ok(results.every(result => result === "ack"));
    const refs = new Set(events.flatMap(event => (event.payload.itineraries as { itineraryRef: string }[]).map(itinerary => itinerary.itineraryRef)));
    const rows = await pool.query("SELECT id FROM offer_upstream_itineraries");
    assert.deepEqual(new Set(rows.rows.map(row => row.id)), refs);
    assert.equal(Number((await pool.query("SELECT count(*) FROM processed_events")).rows[0].count), new Set(events.map(event => event.eventId)).size);
    assert.equal(await handler.handleBatch!(events), "ack");
    assert.equal((await pool.query("SELECT count(*) FROM offer_upstream_itineraries")).rows[0].count, String(refs.size));
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});
