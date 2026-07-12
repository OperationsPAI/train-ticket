import type { Redis } from "ioredis";
import type { Pool, PoolClient, PoolConfig } from "pg";
import { type IdempotencyRecord, type IdempotencyStore } from "./http.js";
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
}>;
export declare class OutboxRelay {
    private readonly pool;
    private readonly redis;
    private readonly options;
    private stopped;
    private loop?;
    constructor(pool: Pool, redis: Redis, options?: OutboxRelayOptions);
    start(): void;
    stop(): Promise<void>;
    runOnce(): Promise<number>;
    private run;
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
