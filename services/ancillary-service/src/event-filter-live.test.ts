import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import { test } from "node:test";
import { Pool } from "pg";
import type { EventEnvelope } from "@trainticket/ts-kit";
import { handlesAncillaryEvent } from "./adapters/messaging/event-filter.js";
import { handleAncillaryUpstreamEvent, migrationsDirectory } from "./adapters/storage/runtime.js";

test("ancillary filters recorded events before PostgreSQL and preserves required event transactions", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_ANCILLARY_FILTER_EVENTS,
}, async () => {
  const events: EventEnvelope[] = JSON.parse(await readFile(process.env.TEST_ANCILLARY_FILTER_EVENTS!, "utf8"));
  const required = events.filter(handlesAncillaryEvent);
  assert.deepEqual(new Set(required.map(event => event.eventType)), new Set(["JourneyOrderCancelled", "EntitlementVoided", "PaymentCaptured", "RefundSettled"]));
  const ignored = events.filter(event => !handlesAncillaryEvent(event)).slice(0, 500);
  assert.equal(ignored.length, 500);
  const unavailable = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 1 });
  await unavailable.end();
  for (const event of ignored) assert.equal(await handleAncillaryUpstreamEvent(unavailable, event), "ack");
  const schema = "ancillary_filter_" + randomUUID().replaceAll("-", "");
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 1 });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, options: `-c search_path=${schema}`, max: 4 });
  try {
    for (const name of (await readdir(migrationsDirectory())).filter(name => name.endsWith(".sql")).sort()) {
      await pool.query(await readFile(`${migrationsDirectory()}/${name}`, "utf8"));
    }
    const cancelled = required.find(event => event.eventType === "JourneyOrderCancelled")!;
    const voided = required.find(event => event.eventType === "EntitlementVoided")!;
    for (const event of [cancelled, voided]) {
      assert.equal(await handleAncillaryUpstreamEvent(pool, event), "ack");
      assert.equal(await handleAncillaryUpstreamEvent(pool, event), "ack");
    }
    assert.equal((await pool.query("SELECT count(*) FROM processed_events")).rows[0].count, "2");
    assert.equal((await pool.query("SELECT event_id FROM ancillary_voided_tickets WHERE entitlement_id=$1", [voided.payload.entitlementId])).rows[0].event_id, voided.eventId);
    for (const eventType of ["PaymentCaptured", "RefundSettled"]) {
      const event = required.find(candidate => candidate.eventType === eventType)!;
      await assert.rejects(handleAncillaryUpstreamEvent(pool, event), /Ancillary order item was not found/);
      assert.equal((await pool.query("SELECT count(*) FROM processed_events WHERE event_id=$1", [event.eventId])).rows[0].count, "0");
    }
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});
