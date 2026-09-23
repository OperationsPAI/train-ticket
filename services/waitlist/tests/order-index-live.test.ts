import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import test from "node:test";
import { Pool } from "pg";
import { PostgresWaitlistRepository } from "../src/storage.js";

test("recorded waitlist rows use the order index for unrelated confirmations", {
  skip: !process.env.TEST_WAITLIST_ROWS || !process.env.TEST_DATABASE_URL || !process.env.TEST_RECORDED_ORDER,
}, async () => {
  const rows = JSON.parse(await readFile(process.env.TEST_WAITLIST_ROWS!, "utf8"));
  assert.ok(rows.length > 10_000);
  const schema = `waitlist_order_${randomUUID().replaceAll("-", "")}`;
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 1 });
  const sql = "SELECT * FROM waitlist_entries WHERE data->>'journeyOrderRef' = $1 ORDER BY created_at ASC LIMIT 1";
  const order = JSON.parse(await readFile(process.env.TEST_RECORDED_ORDER!, "utf8")).orderId;
  assert.ok(order.startsWith("ord-"));
  try {
    await pool.query(`CREATE SCHEMA ${schema}`);
    await pool.query(`SET search_path TO ${schema}`);
    await pool.query(await readFile("migrations/001_waitlist.sql", "utf8"));
    await pool.query("INSERT INTO waitlist_entries SELECT * FROM jsonb_populate_recordset(NULL::waitlist_entries,$1::jsonb)", [JSON.stringify(rows)]);
    await pool.query("ANALYZE waitlist_entries");
    const before = await pool.query(sql, [order]);
    assert.equal(before.rows.length, 0);
    await pool.query(await readFile("migrations/006_order_reference_lookup.sql", "utf8"));
    await pool.query("ANALYZE waitlist_entries");
    const after = await pool.query(sql, [order]);
    assert.deepEqual(after.rows, before.rows);
    const plan = await pool.query("EXPLAIN (FORMAT JSON) " + sql, [order]);
    assert.match(JSON.stringify(plan.rows), /waitlist_journey_order_ref_idx/);
    assert.equal(await new PostgresWaitlistRepository(pool).findByJourneyOrderRef(order), undefined);
    assert.equal(Number((await pool.query("SELECT count(*) FROM waitlist_entries")).rows[0].count), rows.length);
  } finally {
    await pool.query("SET search_path TO public");
    await pool.query(`DROP SCHEMA ${schema} CASCADE`);
    await pool.end();
  }
});
