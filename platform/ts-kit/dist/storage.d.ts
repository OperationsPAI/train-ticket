import type { Redis } from "ioredis";
import type { Pool, PoolClient, PoolConfig } from "pg";
import { type IdempotencyRecord, type IdempotencyStore } from "./http.js";
import { type Clock } from "./liveness.js";
import { type EventEnvelope } from "./messaging.js";
export type Database = Pool | PoolClient;
export declare class OptimisticConcurrencyConflict extends Error {
    constructor(message?: string);
}
export type SnapshotRecord<TSnapshot> = Readonly<{
    id: string;
    version: bigint;
    data: TSnapshot;
}>;
export declare function databaseUrl(): string;
export declare function createPostgresPool(config?: PoolConfig | string): Pool;
export declare function withTransaction<T>(pool: Pool, operation: (client: PoolClient) => Promise<T>): Promise<T>;
export declare function checkPostgresReadiness(pool: Pool, timeoutMs?: number): Promise<boolean>;
export declare class MigrationRunner {
    private readonly pool;
    private readonly migrationsDirectory;
    private ready;
    private lastFailure;
    constructor(pool: Pool, migrationsDirectory: string);
    get isReady(): boolean;
    get failure(): unknown;
    apply(): Promise<void>;
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
export declare function superviseMigrations(runner: MigrationRunner, options?: MigrationSupervisorOptions): MigrationSupervisor;
/** One minute. See `superviseMigrations`' stuck-warning escalation. */
export declare const DEFAULT_MIGRATION_STUCK_WARNING_MS = 60000;
/**
 * Start migrations and wait only for the FIRST attempt to settle.
 *
 * Success means the schema is in place before the service accepts traffic --
 * identical to the old `await migrations.apply()` happy path. Failure means the
 * supervisor keeps retrying in the background while the service starts up
 * not-ready, instead of the old behaviour of swallowing the error and wedging
 * `isReady` false forever.
 */
export declare function startMigrations(pool: Pool, migrationsDirectory: string, options?: MigrationSupervisorOptions): Promise<MigrationSupervisor>;
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
export declare function migrationsAwareReadiness(migrations: Pick<MigrationSupervisor, "isReady">, pool: Pool, timeoutMs?: number): () => Promise<boolean>;
export declare class SnapshotRepository<TSnapshot> {
    private readonly db;
    private readonly tableName;
    constructor(db: Database, tableName: string);
    get(id: string): Promise<SnapshotRecord<TSnapshot> | undefined>;
    save(id: string, snapshot: TSnapshot, expectedVersion?: bigint | number): Promise<SnapshotRecord<TSnapshot>>;
}
export declare class OutboxAppender {
    private readonly db;
    constructor(db: Database);
    append(envelope: EventEnvelope, stream?: string): Promise<void>;
    appendMany(messages: readonly Readonly<{
        stream?: string;
        envelope: EventEnvelope;
    }>[]): Promise<void>;
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
export declare class OutboxRelay {
    private readonly pool;
    private readonly redis;
    private readonly options;
    private stopped;
    private loop?;
    private liveness?;
    constructor(pool: Pool, redis: Redis, options?: OutboxRelayOptions);
    start(): void;
    stop(): Promise<void>;
    private supervise;
    /**
     * A caller-supplied failure reporter must never be able to kill the relay.
     * Pre-fix, `run()` called `options.onFailure` directly from its catch block,
     * so a throwing reporter propagated out of `run()` and permanently ended
     * publication with a single swallowed rejection.
     */
    private reportFailure;
    runOnce(): Promise<number>;
    private run;
    private cleanup;
}
export type ProcessedEventInput = Readonly<{
    eventId: string;
    stream?: string;
}>;
export declare class ProcessedEventsGuard {
    private readonly db;
    constructor(db: Database);
    tryStart(eventId: string, stream?: string): Promise<boolean>;
    tryStartMany(events: readonly ProcessedEventInput[]): Promise<string[]>;
    runOnce<T>(eventId: string, stream: string | undefined, handler: () => Promise<T>): Promise<T | undefined>;
}
export declare class PostgresIdempotencyStore implements IdempotencyStore {
    private readonly db;
    constructor(db: Database);
    get(key: string): Promise<IdempotencyRecord | undefined>;
    set(key: string, record: IdempotencyRecord): Promise<IdempotencyRecord | void>;
}
