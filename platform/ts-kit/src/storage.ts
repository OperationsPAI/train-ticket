import { createRequire } from "node:module";
import { readdir, readFile } from "node:fs/promises";
import { basename, join } from "node:path";

import type { Redis } from "ioredis";
import type { Pool, PoolClient, PoolConfig, QueryResult } from "pg";

import { type IdempotencyRecord, type IdempotencyStore } from "./http.js";
import { type EventEnvelope, streamForProducer } from "./messaging.js";

export type Database = Pool | PoolClient;

export class OptimisticConcurrencyConflict extends Error {
  constructor(message = "Snapshot was modified by another writer") {
    super(message);
    this.name = "OptimisticConcurrencyConflict";
  }
}

export type SnapshotRecord<TSnapshot> = Readonly<{
  id: string;
  version: bigint;
  data: TSnapshot;
}>;

export function databaseUrl(): string {
  const url = process.env.DATABASE_URL;
  if (!url || url.trim().length === 0) {
    throw new Error("DATABASE_URL is required for PostgreSQL storage");
  }
  return url;
}

export function createPostgresPool(config: PoolConfig | string = databaseUrl()): Pool {
  const require = createRequire(import.meta.url);
  const pg = require("pg") as { Pool: new (config: PoolConfig) => Pool };
  return new pg.Pool(typeof config === "string" ? { connectionString: config } : config);
}

export async function withTransaction<T>(pool: Pool, operation: (client: PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const result = await operation(client);
    await client.query("COMMIT");
    return result;
  } catch (error) {
    try {
      await client.query("ROLLBACK");
    } catch {
      // Preserve the original error; callers/readiness will observe later DB failures.
    }
    throw error;
  } finally {
    client.release();
  }
}

export async function checkPostgresReadiness(pool: Pool, timeoutMs = 200): Promise<boolean> {
  let client: PoolClient | undefined;
  try {
    client = await withTimeout(pool.connect(), timeoutMs);
    await withTimeout(client.query("SELECT 1"), timeoutMs);
    return true;
  } catch {
    return false;
  } finally {
    client?.release();
  }
}

export class MigrationRunner {
  private ready = false;
  private lastFailure: unknown;

  constructor(
    private readonly pool: Pool,
    private readonly migrationsDirectory: string,
  ) {}

  get isReady(): boolean {
    return this.ready;
  }

  get failure(): unknown {
    return this.lastFailure;
  }

  async apply(): Promise<void> {
    this.ready = false;
    try {
      await this.pool.query("SELECT 1");
      await this.pool.query(`
        CREATE TABLE IF NOT EXISTS schema_migrations (
          version text PRIMARY KEY,
          applied_at timestamptz NOT NULL DEFAULT now()
        )
      `);

      const files = (await readdir(this.migrationsDirectory))
        .filter((file) => /^\d{3}_.+\.sql$/u.test(file))
        .sort((left, right) => left.localeCompare(right));

      for (const file of files) {
        const version = basename(file, ".sql");
        const applied = await this.pool.query("SELECT 1 FROM schema_migrations WHERE version = $1", [version]);
        if ((applied.rowCount ?? 0) > 0) {
          continue;
        }
        const sql = await readFile(join(this.migrationsDirectory, file), "utf8");
        await withTransaction(this.pool, async (client) => {
          await client.query(sql);
          await client.query("INSERT INTO schema_migrations(version) VALUES ($1) ON CONFLICT DO NOTHING", [version]);
        });
      }

      this.lastFailure = undefined;
      this.ready = true;
    } catch (error) {
      this.lastFailure = error;
      this.ready = false;
      throw error;
    }
  }
}

export class SnapshotRepository<TSnapshot> {
  private readonly tableName: string;

  constructor(
    private readonly db: Database,
    tableName: string,
  ) {
    this.tableName = assertSqlIdentifier(tableName, "snapshot table");
  }

  async get(id: string): Promise<SnapshotRecord<TSnapshot> | undefined> {
    const result = await this.db.query(
      `SELECT id, version, data FROM ${this.tableName} WHERE id = $1`,
      [id],
    ) as QueryResult<{ id: string; version: string | number | bigint; data: TSnapshot }>;
    const row = result.rows[0];
    return row ? { id: row.id, version: BigInt(row.version), data: row.data } : undefined;
  }

  async save(id: string, snapshot: TSnapshot, expectedVersion?: bigint | number): Promise<SnapshotRecord<TSnapshot>> {
    if (expectedVersion === undefined) {
      const inserted = await this.db.query(
        `INSERT INTO ${this.tableName} (id, version, data) VALUES ($1, 1, $2)
         ON CONFLICT DO NOTHING
         RETURNING id, version, data`,
        [id, snapshot],
      ) as QueryResult<{ id: string; version: string | number | bigint; data: TSnapshot }>;
      const row = inserted.rows[0];
      if (!row) {
        throw new OptimisticConcurrencyConflict(`Snapshot ${id} already exists`);
      }
      return { id: row.id, version: BigInt(row.version), data: row.data };
    }

    const updated = await this.db.query(
      `UPDATE ${this.tableName}
       SET version = version + 1, data = $2, updated_at = now()
       WHERE id = $1 AND version = $3
       RETURNING id, version, data`,
      [id, snapshot, expectedVersion.toString()],
    ) as QueryResult<{ id: string; version: string | number | bigint; data: TSnapshot }>;
    const row = updated.rows[0];
    if (!row) {
      throw new OptimisticConcurrencyConflict(`Snapshot ${id} was modified by another writer`);
    }
    return { id: row.id, version: BigInt(row.version), data: row.data };
  }
}

export class OutboxAppender {
  constructor(private readonly db: Database) {}

  async append(envelope: EventEnvelope, stream = streamForProducer(envelope.producer)): Promise<void> {
    await this.appendMany([{ stream, envelope }]);
  }

  async appendMany(messages: readonly Readonly<{ stream?: string; envelope: EventEnvelope }>[]): Promise<void> {
    for (const message of messages) {
      await this.db.query(
        `INSERT INTO outbox (event_id, stream, envelope)
         VALUES ($1, $2, $3)
         ON CONFLICT (event_id) DO NOTHING`,
        [message.envelope.eventId, message.stream ?? streamForProducer(message.envelope.producer), message.envelope],
      );
    }
  }
}

export type OutboxRelayOptions = Readonly<{
  pollIntervalMs?: number;
  batchSize?: number;
  streamMaxLen?: number;
  onFailure?: (error: unknown) => void;
}>;

export class OutboxRelay {
  private stopped = true;
  private loop?: Promise<void>;

  constructor(
    private readonly pool: Pool,
    private readonly redis: Redis,
    private readonly options: OutboxRelayOptions = {},
  ) {}

  start(): void {
    if (!this.stopped) {
      return;
    }
    this.stopped = false;
    this.loop = this.run().catch((error) => {
      if (!this.stopped) {
        this.options.onFailure?.(error);
      }
    });
  }

  async stop(): Promise<void> {
    this.stopped = true;
    await this.loop;
  }

  async runOnce(): Promise<number> {
    const result = await this.pool.query(
      `SELECT seq, stream, envelope
       FROM outbox
       WHERE published_at IS NULL
       ORDER BY seq
       LIMIT $1`,
      [this.options.batchSize ?? 100],
    ) as QueryResult<{ seq: string | number | bigint; stream: string; envelope: EventEnvelope }>;

    for (const row of result.rows) {
      await this.redis.xadd(
        row.stream,
        "MAXLEN",
        "~",
        String(this.options.streamMaxLen ?? 100_000),
        "*",
        "envelope",
        JSON.stringify(row.envelope),
      );
      await this.pool.query("UPDATE outbox SET published_at = now() WHERE seq = $1", [row.seq.toString()]);
    }
    return result.rows.length;
  }

  private async run(): Promise<void> {
    const interval = this.options.pollIntervalMs ?? 250;
    while (!this.stopped) {
      try {
        await this.runOnce();
      } catch (error) {
        this.options.onFailure?.(error);
      }
      await sleepUntil(() => this.stopped, interval);
    }
  }
}

export class ProcessedEventsGuard {
  constructor(private readonly db: Database) {}

  async tryStart(eventId: string, stream?: string): Promise<boolean> {
    const result = await this.db.query(
      `INSERT INTO processed_events (event_id, stream)
       VALUES ($1, $2)
       ON CONFLICT DO NOTHING`,
      [eventId, stream ?? null],
    );
    return (result.rowCount ?? 0) > 0;
  }

  async runOnce<T>(eventId: string, stream: string | undefined, handler: () => Promise<T>): Promise<T | undefined> {
    if (!await this.tryStart(eventId, stream)) {
      return undefined;
    }
    return handler();
  }
}

export class PostgresIdempotencyStore implements IdempotencyStore {
  constructor(private readonly db: Database) {}

  async get(key: string): Promise<IdempotencyRecord | undefined> {
    const result = await this.db.query(
      `SELECT request_hash, status_code, response_body
       FROM idempotency_records
       WHERE key = $1`,
      [key],
    ) as QueryResult<{ request_hash: string; status_code: number; response_body: unknown }>;
    const row = result.rows[0];
    return row ? { fingerprint: row.request_hash, statusCode: row.status_code, body: row.response_body } : undefined;
  }

  async set(key: string, record: IdempotencyRecord): Promise<IdempotencyRecord | void> {
    const inserted = await this.db.query(
      `INSERT INTO idempotency_records (key, request_hash, status_code, response_body)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT DO NOTHING
       RETURNING request_hash, status_code, response_body`,
      [key, record.fingerprint, record.statusCode, record.body ?? null],
    ) as QueryResult<{ request_hash: string; status_code: number; response_body: unknown }>;
    if ((inserted.rowCount ?? 0) > 0) {
      return undefined;
    }

    const existing = await this.get(key);
    if (!existing) {
      throw new Error("Idempotency record conflict could not be read back");
    }
    return existing;
  }
}

function assertSqlIdentifier(identifier: string, label: string): string {
  if (!/^[a-z][a-z0-9_]*$/u.test(identifier)) {
    throw new Error(`Invalid ${label} name`);
  }
  return identifier;
}

function withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("PostgreSQL readiness check timed out")), timeoutMs);
    timer.unref?.();
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, milliseconds);
    timer.unref?.();
  });
}

async function sleepUntil(stopped: () => boolean, milliseconds: number): Promise<void> {
  const deadline = Date.now() + milliseconds;
  while (!stopped() && Date.now() < deadline) {
    await sleep(Math.min(50, deadline - Date.now()));
  }
}
