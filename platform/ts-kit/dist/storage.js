import { createRequire } from "node:module";
import { readdir, readFile } from "node:fs/promises";
import { basename, join } from "node:path";
import { streamForProducer } from "./messaging.js";
export class OptimisticConcurrencyConflict extends Error {
    constructor(message = "Snapshot was modified by another writer") {
        super(message);
        this.name = "OptimisticConcurrencyConflict";
    }
}
export function databaseUrl() {
    const url = process.env.DATABASE_URL;
    if (!url || url.trim().length === 0) {
        throw new Error("DATABASE_URL is required for PostgreSQL storage");
    }
    return url;
}
export function createPostgresPool(config = databaseUrl()) {
    const require = createRequire(import.meta.url);
    const pg = require("pg");
    return new pg.Pool(typeof config === "string" ? { connectionString: config } : config);
}
export async function withTransaction(pool, operation) {
    const client = await pool.connect();
    try {
        await client.query("BEGIN");
        const result = await operation(client);
        await client.query("COMMIT");
        return result;
    }
    catch (error) {
        try {
            await client.query("ROLLBACK");
        }
        catch {
            // Preserve the original error; callers/readiness will observe later DB failures.
        }
        throw error;
    }
    finally {
        client.release();
    }
}
export async function checkPostgresReadiness(pool, timeoutMs = 200) {
    let client;
    try {
        client = await withTimeout(pool.connect(), timeoutMs);
        await withTimeout(client.query("SELECT 1"), timeoutMs);
        return true;
    }
    catch {
        return false;
    }
    finally {
        client?.release();
    }
}
export class MigrationRunner {
    pool;
    migrationsDirectory;
    ready = false;
    lastFailure;
    constructor(pool, migrationsDirectory) {
        this.pool = pool;
        this.migrationsDirectory = migrationsDirectory;
    }
    get isReady() {
        return this.ready;
    }
    get failure() {
        return this.lastFailure;
    }
    async apply() {
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
        }
        catch (error) {
            this.lastFailure = error;
            this.ready = false;
            throw error;
        }
    }
}
export class SnapshotRepository {
    db;
    tableName;
    constructor(db, tableName) {
        this.db = db;
        this.tableName = assertSqlIdentifier(tableName, "snapshot table");
    }
    async get(id) {
        const result = await this.db.query(`SELECT id, version, data FROM ${this.tableName} WHERE id = $1`, [id]);
        const row = result.rows[0];
        return row ? { id: row.id, version: BigInt(row.version), data: row.data } : undefined;
    }
    async save(id, snapshot, expectedVersion) {
        if (expectedVersion === undefined) {
            const inserted = await this.db.query(`INSERT INTO ${this.tableName} (id, version, data) VALUES ($1, 1, $2)
         ON CONFLICT DO NOTHING
         RETURNING id, version, data`, [id, snapshot]);
            const row = inserted.rows[0];
            if (!row) {
                throw new OptimisticConcurrencyConflict(`Snapshot ${id} already exists`);
            }
            return { id: row.id, version: BigInt(row.version), data: row.data };
        }
        const updated = await this.db.query(`UPDATE ${this.tableName}
       SET version = version + 1, data = $2, updated_at = now()
       WHERE id = $1 AND version = $3
       RETURNING id, version, data`, [id, snapshot, expectedVersion.toString()]);
        const row = updated.rows[0];
        if (!row) {
            throw new OptimisticConcurrencyConflict(`Snapshot ${id} was modified by another writer`);
        }
        return { id: row.id, version: BigInt(row.version), data: row.data };
    }
}
export class OutboxAppender {
    db;
    constructor(db) {
        this.db = db;
    }
    async append(envelope, stream = streamForProducer(envelope.producer)) {
        await this.appendMany([{ stream, envelope }]);
    }
    async appendMany(messages) {
        for (const message of messages) {
            await this.db.query(`INSERT INTO outbox (event_id, stream, envelope)
         VALUES ($1, $2, $3)
         ON CONFLICT (event_id) DO NOTHING`, [message.envelope.eventId, message.stream ?? streamForProducer(message.envelope.producer), message.envelope]);
        }
    }
}
export class OutboxRelay {
    pool;
    redis;
    options;
    stopped = true;
    loop;
    constructor(pool, redis, options = {}) {
        this.pool = pool;
        this.redis = redis;
        this.options = options;
    }
    start() {
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
    async stop() {
        this.stopped = true;
        await this.loop;
    }
    async runOnce() {
        const result = await this.pool.query(`SELECT seq, stream, envelope
       FROM outbox
       WHERE published_at IS NULL
       ORDER BY seq
       LIMIT $1`, [this.options.batchSize ?? 100]);
        for (const row of result.rows) {
            await this.redis.xadd(row.stream, "MAXLEN", "~", String(this.options.streamMaxLen ?? 100_000), "*", "envelope", JSON.stringify(row.envelope));
            await this.pool.query("UPDATE outbox SET published_at = now() WHERE seq = $1", [row.seq.toString()]);
        }
        return result.rows.length;
    }
    async run() {
        const interval = this.options.pollIntervalMs ?? 250;
        while (!this.stopped) {
            try {
                await this.runOnce();
            }
            catch (error) {
                this.options.onFailure?.(error);
            }
            await sleepUntil(() => this.stopped, interval);
        }
    }
}
export class ProcessedEventsGuard {
    db;
    constructor(db) {
        this.db = db;
    }
    async tryStart(eventId, stream) {
        const result = await this.db.query(`INSERT INTO processed_events (event_id, stream)
       VALUES ($1, $2)
       ON CONFLICT DO NOTHING`, [eventId, stream ?? null]);
        return (result.rowCount ?? 0) > 0;
    }
    async runOnce(eventId, stream, handler) {
        if (!await this.tryStart(eventId, stream)) {
            return undefined;
        }
        return handler();
    }
}
export class PostgresIdempotencyStore {
    db;
    constructor(db) {
        this.db = db;
    }
    async get(key) {
        const result = await this.db.query(`SELECT request_hash, status_code, response_body
       FROM idempotency_records
       WHERE key = $1`, [key]);
        const row = result.rows[0];
        return row ? { fingerprint: row.request_hash, statusCode: row.status_code, body: row.response_body } : undefined;
    }
    async set(key, record) {
        const inserted = await this.db.query(`INSERT INTO idempotency_records (key, request_hash, status_code, response_body)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT DO NOTHING
       RETURNING request_hash, status_code, response_body`, [key, record.fingerprint, record.statusCode, record.body ?? null]);
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
function assertSqlIdentifier(identifier, label) {
    if (!/^[a-z][a-z0-9_]*$/u.test(identifier)) {
        throw new Error(`Invalid ${label} name`);
    }
    return identifier;
}
function withTimeout(promise, timeoutMs) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("PostgreSQL readiness check timed out")), timeoutMs);
        timer.unref?.();
        promise.then((value) => {
            clearTimeout(timer);
            resolve(value);
        }, (error) => {
            clearTimeout(timer);
            reject(error);
        });
    });
}
function sleep(milliseconds) {
    return new Promise((resolve) => {
        const timer = setTimeout(resolve, milliseconds);
        timer.unref?.();
    });
}
async function sleepUntil(stopped, milliseconds) {
    const deadline = Date.now() + milliseconds;
    while (!stopped() && Date.now() < deadline) {
        await sleep(Math.min(50, deadline - Date.now()));
    }
}
