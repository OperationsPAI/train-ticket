import assert from "node:assert/strict";
import { join } from "node:path";
import { describe, it } from "node:test";

import { livenessProbe, livenessRegistry, startMigrations, type SchemaDetail } from "@trainticket/ts-kit";

import { createApp } from "./app.js";

/**
 * Regression test for the 2026-09-07 boot-time database outage.
 *
 * loyalty-membership's shape differed from the other six services and so does
 * its fix. The others did
 *
 *   try { await migrations.apply(); } catch (e) { console.error(e); }
 *
 * and gated readiness on `migrations.isReady`, which wedged `/readyz` at 503
 * forever. This service instead awaited `apply()` bare, so the rejection
 * propagated through `bootstrap()` into `main.ts`'s `process.exit(1)` -- a
 * CRASH LOOP, not a wedge -- and its `ready()` never consulted migration state
 * at all, so a surviving process with an incomplete schema would have answered
 * `/readyz` 200 and taken traffic it could only 500 on.
 *
 * Both are fixed: the supervisor retries in-process (rather than via
 * CrashLoopBackOff, which caps at 5 minutes, discards the Redis connection and
 * outbox relay every cycle, and hides the cause behind a restart count), and
 * `ready()` now gates on migrations like everywhere else.
 */

/** A pg.Pool stand-in that fails the first `failuresBeforeSuccess` attempts. */
class FlakyPool {
  public attempts = 0;
  private readonly applied = new Set<string>();

  constructor(private readonly failuresBeforeSuccess: number) {}

  async query(sql: string, params?: unknown[]): Promise<{ rows: unknown[]; rowCount: number }> {
    if (sql.includes("SELECT 1") && !sql.includes("schema_migrations")) {
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
 * The real migrations directory. `bootstrap.ts` resolves it relative to the
 * emitted file; `npm test` always runs from the package root, so resolve from
 * cwd here instead of depending on the test build layout.
 */
const migrationsDirectory = (): string => join(process.cwd(), "migrations");

/** Fail rather than hang if a promise never settles (e.g. if the retry is removed). */
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

describe("loyalty-membership storage recovers from a migration that fails at boot", () => {
  it("retries in-process instead of crash-looping, and becomes ready without a restart", async () => {
    const pool = new FlakyPool(3);
    const logs: Record<string, unknown>[] = [];

    // Pre-fix this call REJECTED on the first failure and took the process down
    // through main.ts's process.exit(1). It must now resolve.
    const migrations = await startMigrations(pool as never, migrationsDirectory(), {
      ...noBackoff,
      service: "loyalty-membership",
      log: (entry) => logs.push(entry),
    });

    assert.equal(migrations.isReady(), false, "startup proceeds not-ready while the first attempt has failed");

    await within(migrations.applied(), "loyalty-membership storage never became ready after retrying");

    assert.equal(migrations.isReady(), true, "the retry must clear readiness with no restart");
    assert.ok(pool.attempts >= 4, `expected repeated attempts, saw ${pool.attempts}`);
    assert.ok(logs.some((entry) => String(entry.message).includes("retry in background")));
    await migrations.stop();
  });

  it("applies on the first attempt exactly as the pre-fix code did, with no retry noise", async () => {
    const pool = new FlakyPool(0);
    const logs: Record<string, unknown>[] = [];

    const migrations = await startMigrations(pool as never, migrationsDirectory(), {
      ...noBackoff,
      service: "loyalty-membership",
      log: (entry) => logs.push(entry),
    });

    assert.equal(migrations.isReady(), true);
    assert.equal(migrations.attempts(), 1);
    assert.deepEqual(logs, []);
    await migrations.stop();
  });
});

describe("loyalty-membership probes while migrations are still being retried", () => {
  function migratingStorage(schema: SchemaDetail) {
    return { ready: async () => false, schema: () => schema };
  }

  it("answers /readyz 503 and says why, so the pod is diagnosable without reading the source", async () => {
    const app = createApp({ storage: migratingStorage({
      schema: "migrating",
      attempts: 27,
      retryingForMs: 615_000,
      lastError: "connect ECONNREFUSED 10.96.0.12:5432",
    }) });

    const readyz = await app.inject("/readyz");

    assert.equal(readyz.statusCode, 503);
    assert.deepEqual(readyz.json(), {
      status: "not_ready",
      probe: "ready",
      schema: { schema: "migrating", attempts: 27, retryingForMs: 615_000, lastError: "connect ECONNREFUSED 10.96.0.12:5432" },
    });
    await app.close();
  });

  it("reports migration state alongside the existing eventConsumption field", async () => {
    // The two are independent, and the contrast is deliberate: a retrying
    // SUBSCRIBER alone keeps /readyz at 200 (the HTTP API still works), while
    // retrying MIGRATIONS force 503 (the tables the HTTP API needs do not exist).
    const app = createApp({
      storage: migratingStorage({ schema: "migrating", attempts: 3, retryingForMs: 1_200 }),
      eventConsumption: () => "retrying",
    });

    const readyz = await app.inject("/readyz");

    assert.equal(readyz.statusCode, 503);
    assert.deepEqual(readyz.json(), {
      status: "not_ready",
      probe: "ready",
      eventConsumption: "retrying",
      schema: { schema: "migrating", attempts: 3, retryingForMs: 1_200 },
    });
    await app.close();
  });

  it("does NOT trip liveness for a migration that never succeeds", async () => {
    // A migration that has never succeeded is exactly the never-healthy case,
    // and the never-healthy guard is what stops a boot-time database outage
    // from restart-storming all seven TypeScript services. A restart would
    // re-run the same migration against the same down database, so the retry is
    // the recovery and readiness is the honest signal.
    const app = createApp({ storage: migratingStorage({ schema: "migrating", attempts: 99, retryingForMs: 3_600_000 }) });

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
    const app = createApp({ storage: { ready: async () => true, schema: () => ({ schema: "applied", attempts: 4 }) } });

    const readyz = await app.inject("/readyz");

    assert.equal(readyz.statusCode, 200);
    assert.deepEqual(readyz.json(), { status: "ok", probe: "ready" });
    await app.close();
  });

  it("gates readiness on migrations, which the pre-fix ready() never did", async () => {
    // Pre-fix: `ready: () => checkPostgresReadiness(pool)`. A reachable database
    // with an incomplete schema therefore answered 200. Storage that reports
    // not-ready must produce 503 regardless of raw connectivity.
    const app = createApp({ storage: { ready: async () => false } });

    const readyz = await app.inject("/readyz");

    assert.equal(readyz.statusCode, 503);
    assert.deepEqual(readyz.json(), { status: "not_ready", probe: "ready" });
    await app.close();
  });
});
