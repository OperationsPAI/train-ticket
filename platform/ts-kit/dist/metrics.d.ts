import { type Meter } from "@opentelemetry/api";
import type { Pool } from "pg";
/**
 * The pool this kit creates, named so the attribute is comparable across
 * services. Every TypeScript service runs exactly one.
 */
export declare const POOL_NAME = "platform-ts-kit-postgres";
export declare function otelMetricsEnabled(): boolean;
/**
 * The periodic reader's interval, from the standard SDK variable in
 * milliseconds. A value that is not a positive number falls back to the
 * specification's own default of 60 seconds: this governs only how often metrics
 * are sent, and a service must still start.
 */
export declare function metricExportIntervalMillis(): number;
/**
 * The periodic reader's per-export timeout, from the standard SDK variable.
 *
 * Read here rather than left to the SDK because PeriodicExportingMetricReader
 * throws when the timeout exceeds the interval, and the two defaults are 30
 * seconds and 60 seconds, so any interval below 30 seconds is a combination the
 * reader refuses to construct. Measured: the deployment sets a 15 second
 * interval, and all seven TypeScript services died at startup on
 * "exportIntervalMillis must be greater than or equal to exportTimeoutMillis"
 * while the Java and Python services, whose readers do not validate the pair,
 * ran with a timeout twice their interval.
 *
 * Bounded by the interval for that reason. An export given longer than the
 * interval can still be running when the next one is due, so a timeout above it
 * is not a configuration worth honouring in any language.
 */
export declare function metricExportTimeoutMillis(): number;
/**
 * Publish a pg pool's state under the database client semantic conventions.
 *
 * Observable instruments rather than synchronous ones: occupancy is a level to be
 * sampled, and pg already maintains the three numbers. A callback reading them is
 * cheaper than mirroring every checkout and cannot drift from the pool's own
 * view.
 *
 * A saturated pool reads as db.client.connection.pending_requests rising while
 * db.client.connection.count{state=used} sits at db.client.connection.max.
 *
 * There is no timeout counter. pg rejects an acquisition that exceeds
 * connectionTimeoutMillis by throwing from `connect()` and keeps no count of it,
 * and the pool exposes no hook to observe one, so a counter here would have to
 * be fed from every call site -- see createPostgresPool in storage.ts, where the
 * acquisition happens inside withTransaction and the readiness probe.
 */
export declare function registerPoolMetrics(meter: Meter, pool: Pool, poolName?: string): void;
/**
 * Register pool metrics against the global meter, when metrics are enabled.
 *
 * Called from createPostgresPool so every service is covered without an edit of
 * its own, and a no-op otherwise.
 */
export declare function registerPoolMetricsFromEnv(pool: Pool, poolName?: string): void;
export declare const EVENT_CONSUMER_MESSAGES_METRIC = "train_ticket.event.consumer.messages";
export declare const EVENT_CONSUMER_FAILURES_METRIC = "train_ticket.event.consumer.failures";
export declare const EVENT_CONSUMER_DEAD_LETTERS_METRIC = "train_ticket.event.consumer.dead_letters";
export declare const OUTBOX_UNPUBLISHED_SAMPLE_METRIC = "train_ticket.outbox.unpublished.sample";
export declare const OUTBOX_OLDEST_UNPUBLISHED_AGE_METRIC = "train_ticket.outbox.oldest_unpublished.age";
export declare const OUTBOX_PUBLISHED_METRIC = "train_ticket.outbox.published";
export declare const OUTBOX_FAILURES_METRIC = "train_ticket.outbox.failures";
export declare const EVENT_CONSUMER_MESSAGES_DESCRIPTION = "Event deliveries by their processing disposition.";
export declare const EVENT_CONSUMER_FAILURES_DESCRIPTION = "Event-consumer failures by a bounded failure classification.";
export declare const EVENT_CONSUMER_DEAD_LETTERS_DESCRIPTION = "Messages successfully handed to a dead-letter stream.";
export declare const OUTBOX_UNPUBLISHED_SAMPLE_DESCRIPTION = "Rows visible in the relay's bounded unpublished head sample.";
export declare const OUTBOX_OLDEST_UNPUBLISHED_AGE_DESCRIPTION = "Age of the head-of-line unpublished outbox event.";
export declare const OUTBOX_PUBLISHED_DESCRIPTION = "Outbox events whose bounded Redis publish pipeline completed successfully.";
export declare const OUTBOX_FAILURES_DESCRIPTION = "Outbox relay failures by a bounded processing stage.";
export declare const EVENT_CONSUMER_MESSAGES_UNIT = "{message}";
export declare const EVENT_CONSUMER_FAILURES_UNIT = "{failure}";
export declare const EVENT_CONSUMER_DEAD_LETTERS_UNIT = "{message}";
export declare const OUTBOX_UNPUBLISHED_SAMPLE_UNIT = "{event}";
export declare const OUTBOX_OLDEST_UNPUBLISHED_AGE_UNIT = "s";
export declare const OUTBOX_PUBLISHED_UNIT = "{event}";
export declare const OUTBOX_FAILURES_UNIT = "{failure}";
/** Disposition of one event delivery. */
export type EventConsumerOutcome = "success" | "duplicate" | "retry" | "dead_letter";
/** Bounded classification of an event-consumer failure. */
export type EventConsumerFailureType = "handler_exception" | "transient_result" | "fatal_result" | "missing_envelope" | "invalid_envelope" | "redelivery_exhausted" | "dlq_write";
export interface EventConsumerMetrics {
    recordOutcome(outcome: EventConsumerOutcome): void;
    recordFailure(failureType: EventConsumerFailureType): void;
    recordDeadLetter(reason: EventConsumerFailureType): void;
}
/** Bounded processing stage of an outbox relay failure. */
export type OutboxFailureStage = "select" | "publish" | "mark_published";
export interface OutboxMetrics {
    /**
     * Records the bounded head-of-line sample returned by the relay query.
     *
     * The sample is not presented as a full-table count: it is capped by the relay
     * batch size. A value equal to that cap means at least one full batch is
     * waiting. This avoids an exact `count(*)` scan becoming a new source of
     * database pressure during a large backlog.
     */
    observeUnpublishedSample(rows: number, oldestUnpublishedAgeMillis: number): void;
    /** Records rows whose bounded Redis pipeline completed successfully. */
    recordPublished(rows: number): void;
    recordFailure(stage: OutboxFailureStage): void;
}
/** No-op instruments, used when OTEL metrics are disabled. */
export declare const NOOP_EVENT_CONSUMER_METRICS: EventConsumerMetrics;
export declare const NOOP_OUTBOX_METRICS: OutboxMetrics;
/** Builds the three event-consumer counters against one meter. */
export declare function createEventConsumerMetrics(meter: Meter): EventConsumerMetrics;
/** Builds the outbox relay instruments against one meter. */
export declare function createOutboxMetrics(meter: Meter): OutboxMetrics;
/**
 * Resolves the process-global meter lazily.
 *
 * Subscribers and relays are constructed while a service is still wiring its
 * dependencies, and `@opentelemetry/api` returns the no-op meter until the SDK
 * registers a real provider. Resolving once in the constructor would permanently
 * bind the instruments to that no-op provider. The identity check rebinds
 * exactly when a new provider is installed; steady-state recording is a read of
 * two fields.
 */
export declare class GlobalEventConsumerMetrics implements EventConsumerMetrics {
    private readonly instrumentationName;
    private boundProvider?;
    private delegate?;
    constructor(instrumentationName?: string);
    recordOutcome(outcome: EventConsumerOutcome): void;
    recordFailure(failureType: EventConsumerFailureType): void;
    recordDeadLetter(reason: EventConsumerFailureType): void;
    private resolve;
}
/** Lazily follows the globally registered SDK, mirroring {@link GlobalEventConsumerMetrics}. */
export declare class GlobalOutboxMetrics implements OutboxMetrics {
    private readonly instrumentationName;
    private boundProvider?;
    private delegate?;
    constructor(instrumentationName?: string);
    observeUnpublishedSample(rows: number, oldestUnpublishedAgeMillis: number): void;
    recordPublished(rows: number): void;
    recordFailure(stage: OutboxFailureStage): void;
    private resolve;
}
/**
 * Event-consumer instruments wired against the global meter, or a no-op when
 * OTEL metrics are disabled.
 */
export declare function eventConsumerMetricsFromEnv(instrumentationName?: string): EventConsumerMetrics;
/**
 * Outbox relay instruments wired against the global meter, or a no-op when OTEL
 * metrics are disabled.
 */
export declare function outboxMetricsFromEnv(instrumentationName?: string): OutboxMetrics;
