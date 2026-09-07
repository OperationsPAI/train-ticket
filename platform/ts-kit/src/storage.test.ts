import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { describe, it } from "node:test";

import { configuredStreamMaxLen } from "./messaging.js";
import { createPostgresPool, OptimisticConcurrencyConflict, OutboxRelay, PostgresIdempotencyStore, SnapshotRepository } from "./storage.js";

type QueryCall = Readonly<{ sql: string; params: unknown[] }>;

class FakeOutboxPool {
  public readonly calls: QueryCall[] = [];
  private readonly rows = [
    { seq: 1, stream: "events:order", envelope: { eventId: "evt-1", producer: "order" } },
    { seq: 2, stream: "events:payment", envelope: { eventId: "evt-2", producer: "payment" } },
  ];

  async query(sql: string, params: unknown[]) {
    this.calls.push({ sql, params });
    if (sql.includes("SELECT seq, stream, envelope")) {
      return { rows: this.rows, rowCount: this.rows.length };
    }
    if (sql.includes("UPDATE outbox SET published_at")) {
      return { rows: [], rowCount: (params as unknown[]).length };
    }
    throw new Error(`Unexpected query ${sql}`);
  }
}

class FakeRedisPipeline {
  public readonly xadds: unknown[][] = [];
  public execs = 0;

  xadd(...args: unknown[]) {
    this.xadds.push(args);
    return this;
  }

  async exec() {
    this.execs++;
    return this.xadds.map(() => [null, "1-0"] as const);
  }
}

class FakePipelineRedis {
  public readonly pipelineInstance = new FakeRedisPipeline();
  public pipelines = 0;

  pipeline() {
    this.pipelines++;
    return this.pipelineInstance;
  }
}

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

describe("createPostgresPool", () => {
  it("attaches background error and connect listeners without replacing pg.Pool reconnect behavior", async () => {
    const pool = createPostgresPool({ connectionString: "postgres://localhost:1/test", max: 1 });
    const loggedErrors: unknown[] = [];
    const loggedInfos: unknown[] = [];
    const originalConsoleError = console.error;
    const originalConsoleInfo = console.info;

    console.error = (value?: unknown) => {
      loggedErrors.push(value);
    };
    console.info = (value?: unknown) => {
      loggedInfos.push(value);
    };

    try {
      assert.equal(pool.listenerCount("error"), 1);
      assert.equal(pool.listenerCount("connect"), 1);

      const backgroundError = Object.assign(new Error("connect ECONNREFUSED 127.0.0.1:5432"), {
        code: "ECONNREFUSED",
      });
      pool.emit("error", backgroundError, new EventEmitter());
      pool.emit("connect", new EventEmitter());

      await assert.rejects(
        () => pool.connect(),
        (error: unknown) => error instanceof Error
          && error.name === "AggregateError"
          && "code" in error
          && error.code === "ECONNREFUSED",
      );
    } finally {
      console.error = originalConsoleError;
      console.info = originalConsoleInfo;
      await pool.end();
    }

    assert.equal(loggedErrors.length, 1);
    assert.deepEqual(loggedErrors[0], {
      name: "pg.Pool",
      message: "background PostgreSQL connection error; idle client will be replaced on demand",
      error: { name: "Error", message: "connect ECONNREFUSED 127.0.0.1:5432", code: "ECONNREFUSED" },
      totalCount: 0,
      idleCount: 0,
      waitingCount: 0,
    });
    assert.deepEqual(loggedInfos[0], {
      name: "pg.Pool",
      message: "PostgreSQL pool connection re-established after background error",
      totalCount: 0,
      idleCount: 0,
      waitingCount: 0,
    });
  });
});

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

describe("OutboxRelay", () => {
  it("publishes a batch through one Redis pipeline and one outbox update", async () => {
    const pool = new FakeOutboxPool();
    const redis = new FakePipelineRedis();
    const relay = new OutboxRelay(pool as never, redis as never, { batchSize: 100, streamMaxLen: 50_000 });

    const count = await relay.runOnce();

    assert.equal(count, 2);
    assert.equal(redis.pipelines, 1);
    assert.equal(redis.pipelineInstance.execs, 1);
    assert.equal(redis.pipelineInstance.xadds.length, 2);
    assert.deepEqual(redis.pipelineInstance.xadds[0].slice(0, 5), ["events:order", "MAXLEN", "~", "50000", "*"]);

    const update = pool.calls.find((call) => call.sql.includes("UPDATE outbox SET published_at"));
    assert.ok(update);
    assert.match(update.sql, /WHERE seq IN \(\$1, \$2\)/u);
    assert.deepEqual(update.params, ["1", "2"]);
  });
});

describe("OutboxRelay stream cap", () => {
  it("defaults its XADD cap to the configured EVENT_STREAM_MAXLEN", async () => {
    const pool = new FakeOutboxPool();
    const redis = new FakePipelineRedis();
    const relay = new OutboxRelay(pool as never, redis as never, { batchSize: 100 });

    await relay.runOnce();

    // No streamMaxLen override: the relay must fall back to the kit-wide cap, not 100k.
    assert.deepEqual(redis.pipelineInstance.xadds[0].slice(0, 5), ["events:order", "MAXLEN", "~", String(configuredStreamMaxLen()), "*"]);
  });
});
