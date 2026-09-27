import process from "node:process";
import { metrics } from "@opentelemetry/api";
import { ATTR_DB_CLIENT_CONNECTION_POOL_NAME, ATTR_DB_CLIENT_CONNECTION_STATE, DB_CLIENT_CONNECTION_STATE_VALUE_IDLE, DB_CLIENT_CONNECTION_STATE_VALUE_USED, METRIC_DB_CLIENT_CONNECTION_COUNT, METRIC_DB_CLIENT_CONNECTION_MAX, METRIC_DB_CLIENT_CONNECTION_PENDING_REQUESTS, } from "@opentelemetry/semantic-conventions/incubating";
/**
 * The pool this kit creates, named so the attribute is comparable across
 * services. Every TypeScript service runs exactly one.
 */
export const POOL_NAME = "platform-ts-kit-postgres";
const DEFAULT_METRIC_EXPORT_INTERVAL_MILLIS = 60_000;
const DEFAULT_METRIC_EXPORT_TIMEOUT_MILLIS = 30_000;
export function otelMetricsEnabled() {
    const exporter = process.env.OTEL_METRICS_EXPORTER?.trim().toLowerCase();
    return Boolean(exporter && exporter !== "none");
}
/**
 * The periodic reader's interval, from the standard SDK variable in
 * milliseconds. A value that is not a positive number falls back to the
 * specification's own default of 60 seconds: this governs only how often metrics
 * are sent, and a service must still start.
 */
export function metricExportIntervalMillis() {
    const configured = process.env.OTEL_METRIC_EXPORT_INTERVAL?.trim();
    if (!configured) {
        return DEFAULT_METRIC_EXPORT_INTERVAL_MILLIS;
    }
    const millis = Number.parseInt(configured, 10);
    return Number.isFinite(millis) && millis > 0 ? millis : DEFAULT_METRIC_EXPORT_INTERVAL_MILLIS;
}
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
export function metricExportTimeoutMillis() {
    const interval = metricExportIntervalMillis();
    const configured = process.env.OTEL_METRIC_EXPORT_TIMEOUT?.trim();
    const millis = configured ? Number.parseInt(configured, 10) : DEFAULT_METRIC_EXPORT_TIMEOUT_MILLIS;
    const valid = Number.isFinite(millis) && millis > 0 ? millis : DEFAULT_METRIC_EXPORT_TIMEOUT_MILLIS;
    return Math.min(valid, interval);
}
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
export function registerPoolMetrics(meter, pool, poolName = POOL_NAME) {
    const poolAttributes = { [ATTR_DB_CLIENT_CONNECTION_POOL_NAME]: poolName };
    const usedAttributes = {
        ...poolAttributes,
        [ATTR_DB_CLIENT_CONNECTION_STATE]: DB_CLIENT_CONNECTION_STATE_VALUE_USED,
    };
    const idleAttributes = {
        ...poolAttributes,
        [ATTR_DB_CLIENT_CONNECTION_STATE]: DB_CLIENT_CONNECTION_STATE_VALUE_IDLE,
    };
    meter
        .createObservableUpDownCounter(METRIC_DB_CLIENT_CONNECTION_COUNT, {
        description: "The number of connections that are currently in state described by the state attribute.",
        unit: "{connection}",
    })
        .addCallback((result) => {
        // pg reports the pool's total size and how many of those are idle, not how
        // many are checked out, so "used" is the difference.
        result.observe(Math.max(pool.totalCount - pool.idleCount, 0), usedAttributes);
        result.observe(pool.idleCount, idleAttributes);
    });
    meter
        .createObservableUpDownCounter(METRIC_DB_CLIENT_CONNECTION_MAX, {
        description: "The maximum number of open connections allowed.",
        unit: "{connection}",
    })
        .addCallback((result) => {
        // `options.max` is what createPostgresPool set; pg keeps it there rather
        // than on the pool itself.
        result.observe(pool.options.max ?? 0, poolAttributes);
    });
    meter
        .createObservableUpDownCounter(METRIC_DB_CLIENT_CONNECTION_PENDING_REQUESTS, {
        description: "The number of current pending requests for an open connection.",
        unit: "{request}",
    })
        .addCallback((result) => {
        // The signal a pool bound shows up in first: callers queued because every
        // connection is checked out.
        result.observe(pool.waitingCount, poolAttributes);
    });
}
/**
 * Register pool metrics against the global meter, when metrics are enabled.
 *
 * Called from createPostgresPool so every service is covered without an edit of
 * its own, and a no-op otherwise.
 */
export function registerPoolMetricsFromEnv(pool, poolName = POOL_NAME) {
    if (!otelMetricsEnabled()) {
        return;
    }
    registerPoolMetrics(metrics.getMeter(process.env.OTEL_SERVICE_NAME ?? "train-ticket-service"), pool, poolName);
}
// ---------------------------------------------------------------------------
// Process-level transport metrics.
//
// This is the TypeScript port of the Java shared kit's
// `EventConsumerMetrics`/`OutboxMetrics` contract (see
// `platform/java-kit/.../observability/` and the "process-level transport set"
// section of `docs/07-observability/business-metrics.md`). The names, units and
// descriptions are copied verbatim so the `train-ticket-transport` Prometheus
// rules select one series across every runtime.
//
// The attribute enums are deliberately closed. Stream names, consumer names,
// event types and business identifiers are useful in traces and logs, but using
// them as metric attributes would create a series per integration or entity.
// These outcomes are sufficient to distinguish healthy processing,
// deduplication, retry pressure and poison-message handling without that
// cardinality.
// ---------------------------------------------------------------------------
export const EVENT_CONSUMER_MESSAGES_METRIC = "train_ticket.event.consumer.messages";
export const EVENT_CONSUMER_FAILURES_METRIC = "train_ticket.event.consumer.failures";
export const EVENT_CONSUMER_DEAD_LETTERS_METRIC = "train_ticket.event.consumer.dead_letters";
export const OUTBOX_UNPUBLISHED_SAMPLE_METRIC = "train_ticket.outbox.unpublished.sample";
export const OUTBOX_OLDEST_UNPUBLISHED_AGE_METRIC = "train_ticket.outbox.oldest_unpublished.age";
export const OUTBOX_PUBLISHED_METRIC = "train_ticket.outbox.published";
export const OUTBOX_FAILURES_METRIC = "train_ticket.outbox.failures";
export const EVENT_CONSUMER_MESSAGES_DESCRIPTION = "Event deliveries by their processing disposition.";
export const EVENT_CONSUMER_FAILURES_DESCRIPTION = "Event-consumer failures by a bounded failure classification.";
export const EVENT_CONSUMER_DEAD_LETTERS_DESCRIPTION = "Messages successfully handed to a dead-letter stream.";
export const OUTBOX_UNPUBLISHED_SAMPLE_DESCRIPTION = "Rows visible in the relay's bounded unpublished head sample.";
export const OUTBOX_OLDEST_UNPUBLISHED_AGE_DESCRIPTION = "Age of the head-of-line unpublished outbox event.";
export const OUTBOX_PUBLISHED_DESCRIPTION = "Outbox events whose bounded Redis publish pipeline completed successfully.";
export const OUTBOX_FAILURES_DESCRIPTION = "Outbox relay failures by a bounded processing stage.";
export const EVENT_CONSUMER_MESSAGES_UNIT = "{message}";
export const EVENT_CONSUMER_FAILURES_UNIT = "{failure}";
export const EVENT_CONSUMER_DEAD_LETTERS_UNIT = "{message}";
export const OUTBOX_UNPUBLISHED_SAMPLE_UNIT = "{event}";
export const OUTBOX_OLDEST_UNPUBLISHED_AGE_UNIT = "s";
export const OUTBOX_PUBLISHED_UNIT = "{event}";
export const OUTBOX_FAILURES_UNIT = "{failure}";
/** No-op instruments, used when OTEL metrics are disabled. */
export const NOOP_EVENT_CONSUMER_METRICS = {
    recordOutcome: () => { },
    recordFailure: () => { },
    recordDeadLetter: () => { },
};
export const NOOP_OUTBOX_METRICS = {
    observeUnpublishedSample: () => { },
    recordPublished: () => { },
    recordFailure: () => { },
};
/** Builds the three event-consumer counters against one meter. */
export function createEventConsumerMetrics(meter) {
    const messages = meter.createCounter(EVENT_CONSUMER_MESSAGES_METRIC, {
        description: EVENT_CONSUMER_MESSAGES_DESCRIPTION,
        unit: EVENT_CONSUMER_MESSAGES_UNIT,
    });
    const failures = meter.createCounter(EVENT_CONSUMER_FAILURES_METRIC, {
        description: EVENT_CONSUMER_FAILURES_DESCRIPTION,
        unit: EVENT_CONSUMER_FAILURES_UNIT,
    });
    const deadLetters = meter.createCounter(EVENT_CONSUMER_DEAD_LETTERS_METRIC, {
        description: EVENT_CONSUMER_DEAD_LETTERS_DESCRIPTION,
        unit: EVENT_CONSUMER_DEAD_LETTERS_UNIT,
    });
    return {
        recordOutcome: (outcome) => messages.add(1, { outcome }),
        recordFailure: (failureType) => failures.add(1, { "failure.type": failureType }),
        recordDeadLetter: (reason) => deadLetters.add(1, { reason }),
    };
}
/** Builds the outbox relay instruments against one meter. */
export function createOutboxMetrics(meter) {
    // Async instruments: the head-of-line sample is a level to be sampled, and the
    // relay keeps no running total of it. The callbacks read the last observed
    // value, so a collection is the relay query's own view rather than a mirror of
    // every mutation.
    const sample = {
        rows: 0,
        oldestUnpublishedAgeMillis: 0,
    };
    meter
        .createObservableGauge(OUTBOX_UNPUBLISHED_SAMPLE_METRIC, {
        description: OUTBOX_UNPUBLISHED_SAMPLE_DESCRIPTION,
        unit: OUTBOX_UNPUBLISHED_SAMPLE_UNIT,
    })
        .addCallback((result) => result.observe(sample.rows));
    meter
        .createObservableGauge(OUTBOX_OLDEST_UNPUBLISHED_AGE_METRIC, {
        description: OUTBOX_OLDEST_UNPUBLISHED_AGE_DESCRIPTION,
        unit: OUTBOX_OLDEST_UNPUBLISHED_AGE_UNIT,
    })
        .addCallback((result) => result.observe(sample.oldestUnpublishedAgeMillis / 1000));
    const published = meter.createCounter(OUTBOX_PUBLISHED_METRIC, {
        description: OUTBOX_PUBLISHED_DESCRIPTION,
        unit: OUTBOX_PUBLISHED_UNIT,
    });
    const failures = meter.createCounter(OUTBOX_FAILURES_METRIC, {
        description: OUTBOX_FAILURES_DESCRIPTION,
        unit: OUTBOX_FAILURES_UNIT,
    });
    return {
        observeUnpublishedSample(rows, oldestUnpublishedAgeMillis) {
            sample.rows = Math.max(rows, 0);
            sample.oldestUnpublishedAgeMillis = Math.max(oldestUnpublishedAgeMillis, 0);
        },
        recordPublished(rows) {
            if (rows > 0) {
                published.add(rows);
            }
        },
        recordFailure(stage) {
            failures.add(1, { stage });
        },
    };
}
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
export class GlobalEventConsumerMetrics {
    instrumentationName;
    boundProvider;
    delegate;
    constructor(instrumentationName = defaultTransportInstrumentationName()) {
        this.instrumentationName = instrumentationName;
    }
    recordOutcome(outcome) {
        this.resolve().recordOutcome(outcome);
    }
    recordFailure(failureType) {
        this.resolve().recordFailure(failureType);
    }
    recordDeadLetter(reason) {
        this.resolve().recordDeadLetter(reason);
    }
    resolve() {
        const provider = metrics.getMeterProvider();
        if (this.delegate && this.boundProvider === provider) {
            return this.delegate;
        }
        this.delegate = createEventConsumerMetrics(provider.getMeter(this.instrumentationName));
        this.boundProvider = provider;
        return this.delegate;
    }
}
/** Lazily follows the globally registered SDK, mirroring {@link GlobalEventConsumerMetrics}. */
export class GlobalOutboxMetrics {
    instrumentationName;
    boundProvider;
    delegate;
    constructor(instrumentationName = defaultTransportInstrumentationName()) {
        this.instrumentationName = instrumentationName;
    }
    observeUnpublishedSample(rows, oldestUnpublishedAgeMillis) {
        this.resolve().observeUnpublishedSample(rows, oldestUnpublishedAgeMillis);
    }
    recordPublished(rows) {
        this.resolve().recordPublished(rows);
    }
    recordFailure(stage) {
        this.resolve().recordFailure(stage);
    }
    resolve() {
        const provider = metrics.getMeterProvider();
        if (this.delegate && this.boundProvider === provider) {
            return this.delegate;
        }
        this.delegate = createOutboxMetrics(provider.getMeter(this.instrumentationName));
        this.boundProvider = provider;
        return this.delegate;
    }
}
/**
 * Event-consumer instruments wired against the global meter, or a no-op when
 * OTEL metrics are disabled.
 */
export function eventConsumerMetricsFromEnv(instrumentationName = defaultTransportInstrumentationName()) {
    if (!otelMetricsEnabled()) {
        return NOOP_EVENT_CONSUMER_METRICS;
    }
    return new GlobalEventConsumerMetrics(instrumentationName);
}
/**
 * Outbox relay instruments wired against the global meter, or a no-op when OTEL
 * metrics are disabled.
 */
export function outboxMetricsFromEnv(instrumentationName = defaultTransportInstrumentationName()) {
    if (!otelMetricsEnabled()) {
        return NOOP_OUTBOX_METRICS;
    }
    return new GlobalOutboxMetrics(instrumentationName);
}
function defaultTransportInstrumentationName() {
    return process.env.OTEL_SERVICE_NAME ?? "train-ticket-service";
}
