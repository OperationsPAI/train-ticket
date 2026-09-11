import { createRequire } from "node:module";
import { readdir, readFile } from "node:fs/promises";
import { basename, join } from "node:path";

import type { Redis } from "ioredis";
import type { Pool, PoolClient, PoolConfig, QueryResult } from "pg";

import { type IdempotencyRecord, type IdempotencyStore } from "./http.js";
import { registerLivenessComponent, type Clock, type LivenessComponent } from "./liveness.js";
import { configuredStreamMaxLen, type EventEnvelope, redisRetryStrategy, streamForProducer } from "./messaging.js";

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
  const base: PoolConfig = typeof config === "string" ? { connectionString: config } : config;
  const maxPool = parseInt(process.env.PG_MAX_POOL_SIZE || "", 10);
  const pool = new pg.Pool({
    ...base,
    max: maxPool > 0 ? maxPool : base.max ?? 10,
    idleTimeoutMillis: base.idleTimeoutMillis ?? 30_000,
    connectionTimeoutMillis: base.connectionTimeoutMillis ?? 10_000,
  });
  let backgroundErrorCount = 0;
  pool.on("error", (err: Error) => {
    backgroundErrorCount += 1;
    console.error({
      name: "pg.Pool",
      message: "background PostgreSQL connection error; idle client will be replaced on demand",
      error: sanitizedPostgresPoolError(err),
      totalCount: pool.totalCount,
      idleCount: pool.idleCount,
      waitingCount: pool.waitingCount,
    });
  });
  pool.on("connect", () => {
    const reconnected = backgroundErrorCount > 0;
    if (reconnected) {
      backgroundErrorCount = 0;
    }
    console.info({
      name: "pg.Pool",
      message: reconnected
        ? "PostgreSQL pool connection re-established after background error"
        : "PostgreSQL pool connection established",
      totalCount: pool.totalCount,
      idleCount: pool.idleCount,
      waitingCount: pool.waitingCount,
    });
  });
  return pool;
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

// A probe timeout leaves `SELECT 1` in flight -- nothing cancels it. Returning
// that connection to the pool hands the next borrower this probe's response as
// its own, and the double release that follows throws from inside a pg callback
// where no caller can catch it, taking the process down. Passing an error to
// release destroys the connection instead of pooling it, which is the only safe
// disposal for a connection with unread results.
const ABANDONED = new Error("readiness probe abandoned this connection");

export async function checkPostgresReadiness(pool: Pool, timeoutMs = 200): Promise<boolean> {
  const connectPromise = pool.connect();
  let client: PoolClient | undefined;
  try {
    client = await withTimeout(connectPromise, timeoutMs);
  } catch {
    // The connect itself timed out, so there is no client to release here; the
    // pool may still hand one out later, and that one has to be disposed of too.
    connectPromise.then((late) => late.release(ABANDONED)).catch(() => {});
    return false;
  }
  try {
    await withTimeout(client.query("SELECT 1"), timeoutMs);
    client.release();
    return true;
  } catch {
    client.release(ABANDONED);
    return false;
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

/** "migrating" until a migration run has succeeded, "applied" after. */
export type SchemaState = "migrating" | "applied";

/**
 * Migration retry state for a `/readyz` body.
 *
 * A pod stuck retrying migrations must be diagnosable from its probes alone,
 * not only from its logs -- `curl /readyz` should say why it is 503.
 */
export type SchemaDetail = Readonly<{
  schema: SchemaState;
  attempts: number;
  retryingForMs?: number;
  lastError?: string;
}>;

export type MigrationSupervisorOptions = Readonly<{
  /**
   * Run one migration attempt. Rejects if it failed.
   * Defaults to `runner.apply()`.
   */
  apply?: () => Promise<void>;
  /** Capped exponential backoff. Defaults to the shared Redis reconnect schedule (100ms -> 30s). */
  backoffMs?: (attempt: number) => number;
  sleep?: (milliseconds: number) => Promise<void>;
  log?: (entry: Record<string, unknown>) => void;
  /** Service id used in log entries and in the periodic stuck-warning. */
  service?: string;
  /**
   * Emit an escalated `console.error`-level entry once the supervisor has been
   * retrying continuously for this long, and every interval thereafter, so a
   * service stuck migrating is diagnosable from its logs alone. Default 60s.
   */
  stuckAfterMs?: number;
  clock?: Clock;
}>;

export type MigrationSupervisor = Readonly<{
  /** "migrating" until a migration attempt has succeeded, "applied" after. */
  state: () => SchemaState;
  /** True once migrations have been applied; the gate `ready()` must consult. */
  isReady: () => boolean;
  /** The most recent failure, retained while retrying. */
  failure: () => unknown;
  /** Number of migration attempts made so far. */
  attempts: () => number;
  /** Milliseconds spent continuously retrying, 0 once applied. */
  retryingForMs: () => number;
  /** Structured detail for a `/readyz` body, so the state is diagnosable without reading logs. */
  detail: () => SchemaDetail;
  /** Resolves once the first attempt has settled, successfully or not. */
  settled: () => Promise<void>;
  /** Resolves once migrations have been applied. */
  applied: () => Promise<void>;
  stop: () => Promise<void>;
}>;

/**
 * Apply migrations, retrying indefinitely with capped exponential backoff.
 *
 * Background: every TypeScript service did
 *
 *   try { await migrations.apply(); } catch (e) { console.error(e); }
 *
 * and then gated readiness on `migrations.isReady`. A migration that failed
 * ONCE at startup was therefore never retried: `isReady` stayed false for the
 * life of the process and `/readyz` answered 503 forever. Liveness passed
 * (correctly -- see below), so kubelet never restarted the pod, and the pod sat
 * out of the Service endpoints while happily consuming events. That is exactly
 * what happened on 2026-09-07 when PostgreSQL was OOMKilled at boot: account,
 * customer-service, notification and offer-management all needed a manual
 * `kubectl rollout restart`.
 *
 * The fix is the same idiom the Redis path already uses -- `connectRedisWithRetry`
 * and the `superviseSubscribe` modules -- retry forever on `redisRetryStrategy`'s
 * schedule (100ms -> 30s) so a database that is down or restarting at boot is
 * recovered from without a pod restart.
 *
 * READINESS during the retry window: NOT ready, deliberately. This is the one
 * place where the readiness decision made for the subscribe-retry case is
 * inverted, and the inversion is the point:
 *
 *  - For a dead SUBSCRIBER, readiness stays 200 because the HTTP API still
 *    works perfectly and withdrawing the pod's only endpoint would widen a
 *    partial outage into a total one.
 *  - For missing MIGRATIONS the HTTP API does NOT work: every read and write
 *    goes to tables that do not exist yet, so serving traffic means answering
 *    with `relation "..." does not exist` 500s. Serving reads against a missing
 *    schema is worse than serving them with a dead consumer -- a 503 from a
 *    withdrawn endpoint is an honest "not yet", a 500 from a missing table is a
 *    hard error a caller may treat as terminal.
 *  - So `isReady()` stays false while migrating, which keeps the pre-existing
 *    `migrations.isReady ? checkPostgresReadiness(pool) : false` gate intact.
 *    The change is that it is now a TEMPORARY false that the retry clears, not
 *    a permanent one.
 *
 * EVENT CONSUMPTION during the retry window: left running. Handlers that touch
 * a missing table throw, and the ts-kit subscriber turns a thrown handler error
 * into either a retry (message stays pending, redelivered later) or a DLQ,
 * depending on each service's `thrownHandlerErrors` setting. Blocking the
 * subscribe until migrations land would be a bigger change than this defect
 * warrants and would trade one wedge for another (the subscribe path has its
 * own never-healthy liveness guard). Since the schema gap is measured in
 * seconds once Postgres is back, and the outbox/`processed_events` machinery is
 * idempotent, redelivery is the safe outcome. What we DO change is ordering at
 * boot: the supervisor's first attempt is awaited before the service starts
 * serving, exactly as `apply()` was awaited before, so the happy path is
 * unchanged and a first-time success still means "schema ready before the first
 * request".
 *
 * LIVENESS during the retry window: green, deliberately, and this is correct.
 * `LivenessComponent` only expires a component that has been *continuously*
 * unhealthy past its grace period AND has been healthy at least once
 * (`everHealthy`). A migration that has never succeeded is exactly the
 * never-healthy case, so liveness will not fire -- which is what we want, since
 * restarting the process re-runs the same migration against the same down
 * database and fixes nothing, while a restart storm across seven services makes
 * a database outage worse. Both existing guards are preserved unchanged. The
 * retry is the whole recovery mechanism and readiness is the honest signal;
 * liveness is left to catch the case it can actually fix, a component that used
 * to work and is now wedged. Accordingly the component registered here is
 * marked healthy only once migrations have applied, at which point a LATER
 * database wedge is on the existing `checkPostgresReadiness` path.
 */
export function superviseMigrations(runner: MigrationRunner, options: MigrationSupervisorOptions = {}): MigrationSupervisor {
  const applyOnce = options.apply ?? (() => runner.apply());
  const backoffMs = options.backoffMs ?? redisRetryStrategy;
  const sleepFor = options.sleep ?? sleep;
  const log = options.log ?? ((entry: Record<string, unknown>) => console.warn(entry));
  const service = options.service ?? "service";
  const stuckAfterMs = options.stuckAfterMs ?? DEFAULT_MIGRATION_STUCK_WARNING_MS;
  const clock = options.clock ?? Date.now;

  let state: SchemaState = "migrating";
  let attempts = 0;
  let stopped = false;
  let lastFailure: unknown;
  const startedAt = clock();
  let nextStuckWarningAt = startedAt + stuckAfterMs;

  // Resolved by stop() so a shutdown does not sit through a backoff that may
  // be up to 30s long.
  let interrupt!: () => void;
  const interrupted = new Promise<void>((resolve) => {
    interrupt = resolve;
  });
  let settleFirst!: () => void;
  const firstSettled = new Promise<void>((resolve) => {
    settleFirst = resolve;
  });
  let markApplied!: () => void;
  const appliedPromise = new Promise<void>((resolve) => {
    markApplied = resolve;
  });

  const loop = (async () => {
    for (let attempt = 1; !stopped; attempt += 1) {
      attempts = attempt;
      try {
        await applyOnce();
        if (stopped) {
          return;
        }
        lastFailure = undefined;
        state = "applied";
        if (attempt > 1) {
          log({
            service,
            dependency: "postgres",
            attempt,
            recoveredAfterMs: clock() - startedAt,
            message: "database migrations applied after retry; service became ready without a restart",
          });
        }
        settleFirst();
        markApplied();
        return;
      } catch (error) {
        lastFailure = error;
        settleFirst();
        if (stopped) {
          return;
        }
        const retryInMs = backoffMs(attempt);
        const retryingForMs = clock() - startedAt;
        const entry: Record<string, unknown> = {
          service,
          dependency: "postgres",
          attempt,
          retryInMs,
          retryingForMs,
          error: sanitizedMigrationError(error),
          message: "database migrations failed; service is NOT ready and will retry in background",
        };
        // Escalate periodically so "stuck migrating for ten minutes" is obvious
        // in the logs without anyone reading the source.
        if (retryingForMs >= stuckAfterMs && clock() >= nextStuckWarningAt) {
          nextStuckWarningAt = clock() + stuckAfterMs;
          console.error({
            ...entry,
            message: `database migrations have been failing for ${Math.round(retryingForMs / 1000)}s; /readyz is 503 and this pod is out of the Service endpoints until the database recovers. Liveness stays green on purpose: a restart would re-run the same migration against the same database.`,
          });
        } else {
          log(entry);
        }
        await Promise.race([sleepFor(retryInMs), interrupted]);
      }
    }
  })();

  const retryingForMs = (): number => state === "applied" ? 0 : Math.max(0, clock() - startedAt);

  return {
    state: () => state,
    isReady: () => state === "applied",
    failure: () => lastFailure,
    attempts: () => attempts,
    retryingForMs,
    detail: () => state === "applied"
      ? { schema: state, attempts }
      : {
        schema: state,
        attempts,
        retryingForMs: retryingForMs(),
        ...(lastFailure === undefined ? {} : { lastError: sanitizedMigrationError(lastFailure).message }),
      },
    settled: () => firstSettled,
    applied: () => appliedPromise,
    stop: async () => {
      stopped = true;
      interrupt();
      await loop.catch(() => undefined);
    },
  };
}

/** One minute. See `superviseMigrations`' stuck-warning escalation. */
export const DEFAULT_MIGRATION_STUCK_WARNING_MS = 60_000;

/**
 * Start migrations and wait only for the FIRST attempt to settle.
 *
 * Success means the schema is in place before the service accepts traffic --
 * identical to the old `await migrations.apply()` happy path. Failure means the
 * supervisor keeps retrying in the background while the service starts up
 * not-ready, instead of the old behaviour of swallowing the error and wedging
 * `isReady` false forever.
 */
export async function startMigrations(
  pool: Pool,
  migrationsDirectory: string,
  options: MigrationSupervisorOptions = {},
): Promise<MigrationSupervisor> {
  const supervisor = superviseMigrations(new MigrationRunner(pool, migrationsDirectory), options);
  await supervisor.settled();
  return supervisor;
}

/**
 * The readiness gate every service's `ready()` should use.
 *
 * Two conditions, in order:
 *
 *  1. Migrations must have applied. Until they have, the tables the HTTP API
 *     reads and writes do not exist, so answering 200 would invite traffic that
 *     can only 500. This is the check `loyalty-membership` was missing
 *     entirely -- its `ready()` ran `checkPostgresReadiness(pool)` alone, so a
 *     reachable database with an incomplete schema reported ready.
 *  2. The database must be reachable right now.
 *
 * Note the order matters for cost as well as correctness: while migrations are
 * retrying we answer false without opening a pool connection per probe.
 */
export function migrationsAwareReadiness(
  migrations: Pick<MigrationSupervisor, "isReady">,
  pool: Pool,
  timeoutMs?: number,
): () => Promise<boolean> {
  return () => migrations.isReady() ? checkPostgresReadiness(pool, timeoutMs) : Promise.resolve(false);
}

function sanitizedMigrationError(error: unknown): Readonly<{ name: string; message: string }> {
  if (error instanceof Error) {
    return { name: error.name || "Error", message: error.message || "Database migration failed" };
  }
  return { name: typeof error, message: String(error || "Database migration failed") };
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
  /** Name used for the liveness component; defaults to "outbox-relay". */
  name?: string;
  /** Set false to opt out of liveness registration (tests). */
  trackLiveness?: boolean;
}>;

export class OutboxRelay {
  private stopped = true;
  private loop?: Promise<void>;
  private liveness?: LivenessComponent;

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
    if (this.options.trackLiveness !== false) {
      this.liveness ??= registerLivenessComponent(this.options.name ?? "outbox-relay");
    }
    // The loop must never end while the relay is running. Previously a single
    // rejection out of run() ended publication for the lifetime of the
    // process, with one log line and no restart -- the service looked healthy
    // and shipped no events. Supervise and restart instead.
    this.loop = this.supervise();
  }

  async stop(): Promise<void> {
    this.stopped = true;
    this.liveness?.dispose();
    this.liveness = undefined;
    await this.loop;
  }

  private async supervise(): Promise<void> {
    let restarts = 0;
    while (!this.stopped) {
      try {
        await this.run();
        return;
      } catch (error) {
        if (this.stopped) {
          return;
        }
        restarts += 1;
        this.liveness?.markUnhealthy(`outbox relay loop crashed: ${sanitizedRelayError(error).message}`);
        this.reportFailure(error);
        console.warn({
          name: this.options.name ?? "outbox-relay",
          restarts,
          error: sanitizedRelayError(error),
          message: "outbox relay loop crashed; restarting after backoff",
        });
        await sleep(Math.min(500 * 2 ** Math.min(restarts - 1, 6), 30_000));
      }
    }
  }

  /**
   * A caller-supplied failure reporter must never be able to kill the relay.
   * Pre-fix, `run()` called `options.onFailure` directly from its catch block,
   * so a throwing reporter propagated out of `run()` and permanently ended
   * publication with a single swallowed rejection.
   */
  private reportFailure(error: unknown): void {
    try {
      this.options.onFailure?.(error);
    } catch (reportingError) {
      console.error({
        name: this.options.name ?? "outbox-relay",
        message: "outbox relay failure reporter threw; continuing",
        error: sanitizedRelayError(reportingError),
      });
    }
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

    if (result.rows.length === 0) {
      return 0;
    }

    const pipeline = this.redis.pipeline();
    for (const row of result.rows) {
      pipeline.xadd(
        row.stream,
        "MAXLEN",
        "~",
        String(this.options.streamMaxLen ?? configuredStreamMaxLen()),
        "*",
        "envelope",
        JSON.stringify(row.envelope),
      );
    }
    const publishResults = await pipeline.exec();
    if (!publishResults) {
      throw new Error("Redis pipeline did not return publish results");
    }
    const publishError = publishResults.find(([error]) => error)?.[0];
    if (publishError) {
      throw publishError;
    }

    const seqs = result.rows.map((row) => row.seq.toString());
    const placeholders = seqs.map((_, index) => `$${index + 1}`).join(", ");
    await this.pool.query(`UPDATE outbox SET published_at = now() WHERE seq IN (${placeholders})`, seqs);
    return result.rows.length;
  }

  private async run(): Promise<void> {
    const interval = this.options.pollIntervalMs ?? 250;
    let pollCount = 0;
    while (!this.stopped) {
      try {
        await this.runOnce();
        this.liveness?.markHealthy();
        pollCount++;
        if (pollCount % 20 === 0) {
          await this.cleanup();
        }
      } catch (error) {
        this.liveness?.markUnhealthy(`outbox relay failing: ${sanitizedRelayError(error).message}`);
        this.reportFailure(error);
      }
      await sleepUntil(() => this.stopped, interval);
    }
  }

  private async cleanup(): Promise<void> {
    try {
      await this.pool.query(`DELETE FROM outbox WHERE published_at IS NOT NULL AND published_at < now() - interval '30 seconds'`);
      await this.pool.query(`DELETE FROM processed_events WHERE processed_at < now() - interval '5 minutes'`);
      await this.pool.query(`DELETE FROM idempotency_records WHERE created_at < now() - interval '10 minutes'`);
    } catch {
      // best-effort cleanup
    }
  }
}

export type ProcessedEventInput = Readonly<{ eventId: string; stream?: string }>;

export class ProcessedEventsGuard {
  constructor(private readonly db: Database) {}

  async tryStart(eventId: string, stream?: string): Promise<boolean> {
    const started = await this.tryStartMany([{ eventId, stream }]);
    return started.includes(eventId);
  }

  async tryStartMany(events: readonly ProcessedEventInput[]): Promise<string[]> {
    if (events.length === 0) {
      return [];
    }

    const values: string[] = [];
    const parameters: unknown[] = [];
    for (const [index, event] of events.entries()) {
      parameters.push(event.eventId, event.stream ?? null);
      const first = index * 2 + 1;
      values.push(`($${first}, $${first + 1})`);
    }

    const result = await this.db.query(
      `INSERT INTO processed_events (event_id, stream)
       VALUES ${values.join(", ")}
       ON CONFLICT DO NOTHING
       RETURNING event_id`,
      parameters,
    ) as QueryResult<{ event_id: string }>;
    return result.rows.map((row) => row.event_id);
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

function sanitizedPostgresPoolError(error: Error): Readonly<{ name: string; message: string; code?: string }> {
  const errorWithCode = error as Error & { code?: unknown };
  return {
    name: error.name || "Error",
    message: error.message || "PostgreSQL pool connection error",
    code: typeof errorWithCode.code === "string" ? errorWithCode.code : undefined,
  };
}

function sanitizedRelayError(error: unknown): Readonly<{ name: string; message: string }> {
  if (error instanceof Error) {
    return { name: error.name || "Error", message: error.message || "Outbox relay failed" };
  }
  return { name: typeof error, message: String(error || "Outbox relay failed") };
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
