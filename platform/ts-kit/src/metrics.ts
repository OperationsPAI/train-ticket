import process from "node:process";

import { metrics, type Meter, type ObservableResult } from "@opentelemetry/api";
import {
  ATTR_DB_CLIENT_CONNECTION_POOL_NAME,
  ATTR_DB_CLIENT_CONNECTION_STATE,
  DB_CLIENT_CONNECTION_STATE_VALUE_IDLE,
  DB_CLIENT_CONNECTION_STATE_VALUE_USED,
  METRIC_DB_CLIENT_CONNECTION_COUNT,
  METRIC_DB_CLIENT_CONNECTION_MAX,
  METRIC_DB_CLIENT_CONNECTION_PENDING_REQUESTS,
} from "@opentelemetry/semantic-conventions/incubating";
import type { Pool } from "pg";

/**
 * The pool this kit creates, named so the attribute is comparable across
 * services. Every TypeScript service runs exactly one.
 */
export const POOL_NAME = "platform-ts-kit-postgres";

const DEFAULT_METRIC_EXPORT_INTERVAL_MILLIS = 60_000;
const DEFAULT_METRIC_EXPORT_TIMEOUT_MILLIS = 30_000;

export function otelMetricsEnabled(): boolean {
  const exporter = process.env.OTEL_METRICS_EXPORTER?.trim().toLowerCase();
  return Boolean(exporter && exporter !== "none");
}

/**
 * The periodic reader's interval, from the standard SDK variable in
 * milliseconds. A value that is not a positive number falls back to the
 * specification's own default of 60 seconds: this governs only how often metrics
 * are sent, and a service must still start.
 */
export function metricExportIntervalMillis(): number {
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
export function metricExportTimeoutMillis(): number {
  const interval = metricExportIntervalMillis();
  const configured = process.env.OTEL_METRIC_EXPORT_TIMEOUT?.trim();
  const millis = configured ? Number.parseInt(configured, 10) : DEFAULT_METRIC_EXPORT_TIMEOUT_MILLIS;
  const valid =
    Number.isFinite(millis) && millis > 0 ? millis : DEFAULT_METRIC_EXPORT_TIMEOUT_MILLIS;
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
export function registerPoolMetrics(meter: Meter, pool: Pool, poolName: string = POOL_NAME): void {
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
    .addCallback((result: ObservableResult) => {
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
    .addCallback((result: ObservableResult) => {
      // `options.max` is what createPostgresPool set; pg keeps it there rather
      // than on the pool itself.
      result.observe(pool.options.max ?? 0, poolAttributes);
    });

  meter
    .createObservableUpDownCounter(METRIC_DB_CLIENT_CONNECTION_PENDING_REQUESTS, {
      description: "The number of current pending requests for an open connection.",
      unit: "{request}",
    })
    .addCallback((result: ObservableResult) => {
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
export function registerPoolMetricsFromEnv(pool: Pool, poolName: string = POOL_NAME): void {
  if (!otelMetricsEnabled()) {
    return;
  }
  registerPoolMetrics(metrics.getMeter(process.env.OTEL_SERVICE_NAME ?? "train-ticket-service"), pool, poolName);
}
