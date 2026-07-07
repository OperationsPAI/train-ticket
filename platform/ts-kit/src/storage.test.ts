import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { OptimisticConcurrencyConflict, PostgresIdempotencyStore, SnapshotRepository } from "./storage.js";

type QueryCall = Readonly<{ sql: string; params: unknown[] }>;



class FakeIdempotencyDb {
  private row: { request_hash: string; status_code: number; response_body: unknown } | undefined;

  async query(sql: string, params: unknown[]) {
    if (sql.includes("INSERT INTO idempotency_records")) {
      if (this.row) {
        return { rows: [], rowCount: 0 };
      }
      this.row = {
        request_hash: params[1] as string,
        status_code: params[2] as number,
        response_body: params[3],
      };
      return { rows: [this.row], rowCount: 1 };
    }
    if (sql.includes("SELECT request_hash, status_code, response_body")) {
      return { rows: this.row ? [this.row] : [], rowCount: this.row ? 1 : 0 };
    }
    throw new Error(`Unexpected query ${sql}`);
  }
}

class FakeSnapshotDb {
  public readonly calls: QueryCall[] = [];
  private row: { id: string; version: bigint; data: unknown } | undefined;

  async query(sql: string, params: unknown[]) {
    this.calls.push({ sql, params });
    if (sql.includes("INSERT INTO test_snapshots")) {
      if (this.row !== undefined) {
        return { rows: [], rowCount: 0 };
      }
      this.row = { id: params[0] as string, version: 1n, data: params[1] };
      return { rows: [{ ...this.row, version: this.row.version.toString() }], rowCount: 1 };
    }
    if (sql.includes("UPDATE test_snapshots")) {
      if (!this.row || this.row.id !== params[0] || this.row.version !== BigInt(params[2] as string)) {
        return { rows: [], rowCount: 0 };
      }
      this.row = { id: this.row.id, version: this.row.version + 1n, data: params[1] };
      return { rows: [{ ...this.row, version: this.row.version.toString() }], rowCount: 1 };
    }
    if (sql.includes("SELECT id, version, data FROM test_snapshots")) {
      return { rows: this.row && this.row.id === params[0] ? [{ ...this.row, version: this.row.version.toString() }] : [] };
    }
    throw new Error(`Unexpected query ${sql}`);
  }
}

describe("SnapshotRepository", () => {
  it("inserts new snapshots, updates by expected version, and detects optimistic conflicts", async () => {
    const db = new FakeSnapshotDb();
    const repo = new SnapshotRepository<{ name: string }>(db as never, "test_snapshots");

    const inserted = await repo.save("agg-1", { name: "first" });
    assert.equal(inserted.version, 1n);
    assert.deepEqual((await repo.get("agg-1"))?.data, { name: "first" });

    const updated = await repo.save("agg-1", { name: "second" }, inserted.version);
    assert.equal(updated.version, 2n);
    assert.deepEqual(updated.data, { name: "second" });

    await assert.rejects(
      () => repo.save("agg-1", { name: "stale" }, inserted.version),
      OptimisticConcurrencyConflict,
    );
    assert.match(db.calls.find((call) => call.sql.includes("UPDATE test_snapshots"))?.sql ?? "", /WHERE id = \$1 AND version = \$3/u);
  });

  it("rejects unsafe dynamic table names", () => {
    assert.throws(
      () => new SnapshotRepository(new FakeSnapshotDb() as never, "test_snapshots; drop table outbox"),
      /Invalid snapshot table name/u,
    );
  });
});


describe("PostgresIdempotencyStore", () => {
  it("inserts atomically and returns the already visible record on conflict", async () => {
    const store = new PostgresIdempotencyStore(new FakeIdempotencyDb() as never);

    const inserted = await store.set("key-1", { fingerprint: "hash-a", statusCode: 201, body: { ok: true } });
    assert.equal(inserted, undefined);

    const replay = await store.set("key-1", { fingerprint: "hash-a", statusCode: 500, body: { ignored: true } });
    assert.deepEqual(replay, { fingerprint: "hash-a", statusCode: 201, body: { ok: true } });

    const reused = await store.set("key-1", { fingerprint: "hash-b", statusCode: 202, body: { ignored: true } });
    assert.deepEqual(reused, { fingerprint: "hash-a", statusCode: 201, body: { ok: true } });
  });
});
