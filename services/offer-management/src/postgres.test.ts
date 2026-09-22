import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { it } from "node:test";
import { Pool } from "pg";
import { PostgresUpstreamStateRepository } from "./adapters/storage/upstream-state-repository.js";

it("filters MCT validity before limiting PostgreSQL disclosure results", { skip: !process.env.TEST_DATABASE_URL }, async () => {
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL });
  const client = await pool.connect();
  const schema = `offer_test_${randomUUID().replaceAll("-", "")}`;
  try {
    await client.query(`CREATE SCHEMA ${schema}`);
    await client.query(`SET search_path TO ${schema}`);
    await client.query(await readFile("migrations/001_offer_storage.sql", "utf8"));
    const repository = new PostgresUpstreamStateRepository(client);
    const now = new Date();
    for (let index = 0; index < 30; index++) {
      await repository.saveMctRule({
        mctRuleId: `mct-${String(index).padStart(3, "0")}`, version: 1,
        status: "PUBLISHED", transferCategory: "RAIL_RAIL", minimumMinutes: 20,
        previousStatus: "DRAFT", fromNodeType: "RAIL_STATION", toNodeType: "RAIL_STATION", conditions: {},
        validFrom: new Date(now.getTime() - 60_000),
        validUntil: new Date(now.getTime() + (index < 15 ? -1 : 60_000)), publishedAt: now,
      });
    }
    const rules = await repository.findPublishedMctRules(now, 10);
    assert.equal(rules.length, 10);
    assert.equal(rules[0].mctRuleId, "mct-015");
    assert.ok(rules.every((rule) => rule.validUntil!.getTime() > now.getTime()));
  } finally {
    await client.query(`DROP SCHEMA ${schema} CASCADE`);
    client.release();
    await pool.end();
  }
});
