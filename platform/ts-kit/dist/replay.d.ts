import type { Pool } from "pg";
import { type DeadLetterEvidenceSink, type EventEnvelope, type EventHandlerOutput } from "./messaging.js";
export type EventReplaySource = Readonly<{
    name: string;
    endpoint: string;
    producer: string;
}>;
export type ReplayHandler = (envelope: EventEnvelope, stream: string) => Promise<EventHandlerOutput> | EventHandlerOutput;
export declare const EVENT_REPLAY_RETENTION_DAYS_ENV = "EVENT_REPLAY_RETENTION_DAYS";
export declare const DEFAULT_EVENT_REPLAY_RETENTION_DAYS = 7;
export declare const MAX_EVENT_REPLAY_RETENTION_DAYS = 90;
export declare function eventReplayRetentionMs(configured?: string | undefined): number;
type ReplayArchiveEvent = Readonly<{
    sequence: number;
    eventId: string;
    stream: string;
    envelope: string;
    archivedAt: string;
    retainedUntil: string;
}>;
type ReplayPage = Readonly<{
    producer: string;
    afterSequence: number;
    oldestAvailableSequence: number;
    latestAvailableSequence: number;
    retainedFrom: string;
    hasMore: boolean;
    events: readonly ReplayArchiveEvent[];
}>;
export type DurableEventReplayOptions = Readonly<{
    consumerGroup: string;
    pageSize?: number;
    maxPagesPerCycle?: number;
    pollIntervalMs?: number;
    fetchPage?: (source: EventReplaySource, after: number, limit: number) => Promise<ReplayPage>;
    deadLetterEvidenceSink?: DeadLetterEvidenceSink;
    log?: (entry: Record<string, unknown>) => void;
}>;
export declare class ReplayRetentionGap extends Error {
    readonly source: string;
    readonly detail: unknown;
    constructor(source: string, detail: unknown);
}
export declare class DurableEventReplayWorker {
    private readonly pool;
    private readonly sources;
    private readonly handler;
    private readonly options;
    private stopped;
    private loop?;
    private readonly checkpoints;
    private readonly evidence;
    constructor(pool: Pool, sources: readonly EventReplaySource[], handler: ReplayHandler, options: DurableEventReplayOptions);
    start(): void;
    stop(): Promise<void>;
    runSourceOnce(source: EventReplaySource): Promise<number>;
    private run;
    private pageSize;
}
export declare class PostgresReplayCheckpointStore {
    private readonly pool;
    constructor(pool: Pool);
    getOrCreate(consumerGroup: string, source: EventReplaySource): Promise<number>;
    beginRecovery(consumerGroup: string, source: EventReplaySource, sequence: number, eventId: string): Promise<void>;
    advance(consumerGroup: string, source: EventReplaySource, sequence: number, eventId: string, recovered: boolean): Promise<void>;
    recordRecoveryError(consumerGroup: string, source: EventReplaySource, sequence: number, eventId: string, error: unknown): Promise<void>;
    recordSuccess(consumerGroup: string, source: EventReplaySource): Promise<void>;
    recordError(consumerGroup: string, source: EventReplaySource, error: unknown): Promise<void>;
    recordGap(consumerGroup: string, source: EventReplaySource, detail: unknown): Promise<void>;
}
export {};
