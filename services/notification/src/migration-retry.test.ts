import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";

import { livenessProbe, livenessRegistry, startMigrations, type LivenessComponent, type SchemaDetail } from "@trainticket/ts-kit";

import { createApp } from "./app.js";
import { migrationsDirectory } from "./adapters/storage/runtime.js";

/**
 * Regression test for the 2026-09-07 permanent-503 wedge.
 *
 * PostgreSQL was OOMKilled while the TypeScript services were booting.
 * `startNotificationStorage` did
 *
 *   try { await migrations.apply(); } catch (e) { console.error(e); }
 *
 * so the failure was logged once and never retried. `migrations.isReady` stayed
 * false for the life of the process, `/readyz` answered 503 forever, and the
 * pod sat outside the Service endpoints while consuming events perfectly well.
 * Liveness passed -- correctly, since a restart re-runs the same migration --
 * so kubelet never intervened, and recovery needed a manual
 * `kubectl rollout restart`.
 *
 * These tests drive the real `startMigrations` against a fake pool and assert
 * the three behaviours that discriminate the fix from the defect.
 */

/** A pg.Pool stand-in that fails the first `failures` migration attempts. */
class FlakyPool {
  public attempts = 0;
  private readonly applied = new Set<string>();

  constructor(private readonly failuresBeforeSuccess: number) {}

  async query(sql: string, params?: unknown[]): Promise<{ rows: unknown[]; rowCount: number }> {
    if (sql.includes("SELECT 1") && !sql.includes("schema_migrations")) {
      // The MigrationRunner's connectivity probe: this is what a down or
      // OOMKilled PostgreSQL fails on.
      this.attempts += 1;
      if (this.attempts <= this.failuresBeforeSuccess) {
        throw Object.assign(new Error("connect ECONNREFUSED 10.96.0.12:5432"), { code: "ECONNREFUSED" });
      }
      return { rows: [{ "?column?": 1 }], rowCount: 1 };
    }
    if (sql.includes("SELECT 1 FROM schema_migrations")) {
      const version = String(params?.[0]);
      return this.applied.has(version) ? { rows: [{}], rowCount: 1 } : { rows: [], rowCount: 0 };
    }
    if (sql.includes("INSERT INTO schema_migrations")) {
      this.applied.add(String(params?.[0]));
      return { rows: [], rowCount: 1 };
    }
    return { rows: [], rowCount: 0 };
  }

  async connect(): Promise<{ query: FlakyPool["query"]; release: () => void }> {
    return { query: (sql: string, params?: unknown[]) => this.query(sql, params), release: () => {} };
  }
}

const noBackoff = { backoffMs: () => 0, sleep: async (): Promise<void> => undefined };

/**
 * Await a promise, failing the test rather than hanging the run if it never
 * settles. Without this, deleting the retry would make "becomes ready after a
 * retry" hang forever on `applied()` instead of going red.
 */
async function within<T>(promise: Promise<T>, label: string, timeoutMs = 2_000): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  try {
    return await Promise.race([
      promise,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error(`${label} did not settle within ${timeoutMs}ms`)), timeoutMs);
        timer.unref?.();
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

describe("notification storage recovers from a migration that fails at boot", () => {
  it("becomes ready after a retry, without a process restart", async () => {
    const pool = new FlakyPool(3);
    const logs: Record<string, unknown>[] = [];

    const migrations = await startMigrations(pool as never, migrationsDirectory(), {
      ...noBackoff,
      service: "notification",
      log: (entry) => logs.push(entry),
    });

    // startMigrations only awaits the FIRST attempt, so at this point the
    // service has started up not-ready -- exactly as it should.
    assert.equal(migrations.isReady(), false);
    assert.equal(migrations.state(), "migrating");

    await within(migrations.applied(), "notification storage never became ready after retrying");

    assert.equal(migrations.isReady(), true, "the retry must clear readiness with no restart");
    assert.equal(migrations.state(), "applied");
    assert.ok(pool.attempts >= 4, `expected repeated attempts, saw ${pool.attempts}`);
    assert.ok(
      logs.some((entry) => String(entry.message).includes("retry in background")),
      "each failed attempt must be logged",
    );
    await migrations.stop();
  });

  it("applies on the first attempt exactly as the pre-fix code did, with no retry noise", async () => {
    const pool = new FlakyPool(0);
    const logs: Record<string, unknown>[] = [];

    const migrations = await startMigrations(pool as never, migrationsDirectory(), {
      ...noBackoff,
      service: "notification",
      log: (entry) => logs.push(entry),
    });

    assert.equal(migrations.isReady(), true, "a healthy database must be ready as soon as startMigrations returns");
    assert.equal(migrations.attempts(), 1);
    assert.deepEqual(logs, []);
    await migrations.stop();
  });
});

describe("notification probes while migrations are still being retried", () => {
  const registered: LivenessComponent[] = [];

  afterEach(() => {
    for (const component of registered.splice(0)) {
      component.dispose();
    }
  });

  /** A storage double standing in for one whose migrations have not applied. */
  function migratingStorage(schema: SchemaDetail) {
    return { ready: async () => false, schema: () => schema };
  }

  it("answers /readyz 503 and says why, so the pod is diagnosable without reading the source", async () => {
    const app = createApp({}, migratingStorage({
      schema: "migrating",
      attempts: 27,
      retryingForMs: 615_000,
      lastError: "connect ECONNREFUSED 10.96.0.12:5432",
    }));

    const readyz = await app.inject("/readyz");

    assert.equal(readyz.statusCode, 503);
    assert.deepEqual(readyz.json(), {
      status: "not_ready",
      probe: "ready",
      schema: {
        schema: "migrating",
        attempts: 27,
        retryingForMs: 615_000,
        lastError: "connect ECONNREFUSED 10.96.0.12:5432",
      },
    });
    await app.close();
  });

  it("does NOT trip liveness for a migration that never succeeds", async () => {
    // A migration that never succeeds is the never-healthy case, and the
    // never-healthy guard exists so a boot-time database outage cannot
    // restart-storm all seven TypeScript services at once. A restart would
    // re-run the same migration against the same down database, so the retry
    // is the recovery and readiness is the honest signal.
    const app = createApp({}, migratingStorage({ schema: "migrating", attempts: 99, retryingForMs: 3_600_000 }));

    const healthz = await app.inject("/healthz");

    assert.equal(healthz.statusCode, 200, "migration retries must never fail liveness");
    assert.deepEqual(healthz.json(), { status: "ok", probe: "live" });
    assert.deepEqual(livenessProbe().failed, []);
    assert.equal(
      livenessRegistry.state().components.some((component) => component.name.includes("migration")),
      false,
      "the migration supervisor must not register a component that could expire",
    );
    await app.close();
  });

  it("keeps the unchanged 200 body once migrations have applied", async () => {
    const app = createApp({}, { ready: async () => true, schema: () => ({ schema: "applied", attempts: 4 }) });

    const readyz = await app.inject("/readyz");

    assert.equal(readyz.statusCode, 200);
    // No `schema` key on the happy path: existing clients and tests see the
    // exact body shape they saw before.
    assert.deepEqual(readyz.json(), { status: "ok", probe: "ready" });
    await app.close();
  });

  it("keeps the unchanged 503 body for storage that reports no migration state", async () => {
    const app = createApp({}, { ready: async () => false });

    const readyz = await app.inject("/readyz");

    assert.equal(readyz.statusCode, 503);
    assert.deepEqual(readyz.json(), { status: "not_ready", probe: "ready" });
    await app.close();
  });
});
