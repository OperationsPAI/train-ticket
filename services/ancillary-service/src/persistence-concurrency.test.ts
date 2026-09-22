import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { Pool } from "pg";
import { OptimisticConcurrencyConflict, OutboxAppender, withTransaction, createEventEnvelope } from "@trainticket/ts-kit";
import { PostgresAncillaryRepository } from "./adapters/storage/repository.js";
import { migrationsDirectory } from "./adapters/storage/runtime.js";
import { AncillaryOrderItem, type AncillaryOrderItemSnapshot } from "./domain.js";

test("persisted ancillary versions reject conflicting outcomes without publishing them", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_ANCILLARY_SNAPSHOT_FILE,
}, async () => {
  const snapshot = JSON.parse(await readFile(process.env.TEST_ANCILLARY_SNAPSHOT_FILE!, "utf8")) as AncillaryOrderItemSnapshot;
  assert.equal(snapshot.status, "CONFIRMED");
  const schema = "ancillary_versions_" + randomUUID().replaceAll("-", "");
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 4, options: `-c search_path=${schema}` });
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 1 });
  await admin.query(`CREATE SCHEMA ${schema}`);
  try {
    for (const file of (await readdir(migrationsDirectory())).filter(name => name.endsWith(".sql")).sort()) {
      await pool.query(await readFile(`${migrationsDirectory()}/${file}`, "utf8"));
    }
    await pool.query("INSERT INTO ancillary_order_items(id,version,data) VALUES ($1,$2,$3)", [snapshot.ancillaryOrderItemId, snapshot.aggregateVersion, snapshot]);
    const ready = AncillaryOrderItem.fromSnapshot(snapshot);
    ready.fulfillmentReady({ entitlementRef: snapshot.entitlementRef });
    const cancelled = AncillaryOrderItem.fromSnapshot(snapshot);
    cancelled.cancel({ reasonCode: "JOURNEY_ORDER_CANCELLED", source: "JOURNEY_ORDER_CANCELLED" });
    const repository = new PostgresAncillaryRepository(pool);
    await repository.saveOrderItem(cancelled.toSnapshot());
    await repository.saveOrderItem(cancelled.toSnapshot());
    await assert.rejects(withTransaction(pool, async client => {
      await new PostgresAncillaryRepository(client).saveOrderItem(ready.toSnapshot());
      await new OutboxAppender(client).append(createEventEnvelope({eventType:"AncillaryOrderItemFulfillmentReady",producer:"ancillary-service",payload:{ancillaryOrderItemId:snapshot.ancillaryOrderItemId}}));
    }), OptimisticConcurrencyConflict);
    await assert.rejects(repository.saveOrderItem(snapshot), OptimisticConcurrencyConflict);
    assert.equal((await pool.query("SELECT count(*) FROM outbox")).rows[0].count,"0");
    const stored = await repository.getOrderItem(snapshot.ancillaryOrderItemId);
    assert.deepEqual(stored, JSON.parse(JSON.stringify(cancelled.toSnapshot())));
    const terminal = AncillaryOrderItem.fromSnapshot(stored!);
    assert.equal(terminal.cancel({reasonCode:"JOURNEY_ORDER_CANCELLED",source:"JOURNEY_ORDER_CANCELLED"}), undefined);
    await repository.saveOrderItem(terminal.toSnapshot());
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});
