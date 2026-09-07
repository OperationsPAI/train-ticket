import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { livenessProbe, livenessRegistry, startMigrations, type SchemaDetail } from "@trainticket/ts-kit";

import { createApp } from "./app.js";
import { migrationsDirectory } from "./adapters/storage/runtime.js";

/**
 * Regression test for the 2026-09-07 permanent-503 wedge.
 *
 * PostgreSQL was OOMKilled while the TypeScript services were booting.
 * `startAncillaryStorage` did
 *
 *   try { await migrations.apply(); } catch (e) { console.error(e); }
 *
 * so the failure was logged once and never retried. `migrations.isReady` stayed
 * false for the life of the process, `/readyz` answered 503 forever, and the
 * pod sat outside the Service endpoints while consuming events perfectly well.
 * Liveness passed -- correctly, since a restart re-runs the same migration --
 * so kubelet never intervened, and recovery needed a manual
 * `kubectl rollout restart` of account, customer-service, notification and
 * offer-management.
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

describe("ancillary-service storage recovers from a migration that fails at boot", () => {
  it("becomes ready after a retry, without a process restart", async () => {
    const pool = new FlakyPool(3);
    const logs: Record<string, unknown>[] = [];

    const migrations = await startMigrations(pool as never, migrationsDirectory(), {
      ...noBackoff,
      service: "ancillary-service",
      log: (entry) => logs.push(entry),
    });

    assert.equal(migrations.isReady(), false, "startup proceeds not-ready while the first attempt has failed");
    assert.equal(migrations.state(), "migrating");

    await within(migrations.applied(), "ancillary-service storage never became ready after retrying");

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
      service: "ancillary-service",
      log: (entry) => logs.push(entry),
    });

    assert.equal(migrations.isReady(), true);
    assert.equal(migrations.attempts(), 1);
    assert.deepEqual(logs, []);
    await migrations.stop();
  });
});

describe("ancillary-service probes while migrations are still being retried", () => {
  function migratingStorage(schema: SchemaDetail) {
    return { ready: async () => false, schema: () => schema };
  }

  it("answers /readyz 503 and says why, so the pod is diagnosable without reading the source", async () => {
    const app = createApp({}, { storage: migratingStorage({
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

  it("does NOT trip liveness for a migration that never succeeds", async () => {
    // A migration that has never succeeded is exactly the never-healthy case,
    // and the never-healthy guard is what stops a boot-time database outage
    // from restart-storming all seven TypeScript services. A restart would
    // re-run the same migration against the same down database, so the retry is
    // the recovery and readiness is the honest signal.
    const app = createApp({}, { storage: migratingStorage({ schema: "migrating", attempts: 99, retryingForMs: 3_600_000 }) });

    const healthz = await app.inject("/healthz");
    const livez = await app.inject("/livez");

    assert.equal(healthz.statusCode, 200, "migration retries must never fail liveness");
    // `/healthz` here answers the service-profile body when live (see
    // `healthzBody`), so the probe body is asserted on `/livez`.
    assert.equal(livez.statusCode, 200);
    assert.deepEqual(livez.json(), { status: "ok", probe: "live" });
    assert.deepEqual(livenessProbe().failed, []);
    assert.equal(
      livenessRegistry.state().components.some((component) => component.name.includes("migration")),
      false,
      "the migration supervisor must not register a component that could expire",
    );
    await app.close();
  });

  it("keeps the unchanged 200 body once migrations have applied", async () => {
    const app = createApp({}, { storage: { ready: async () => true, schema: () => ({ schema: "applied", attempts: 4 }) } });

    const readyz = await app.inject("/readyz");

    assert.equal(readyz.statusCode, 200);
    assert.deepEqual(readyz.json(), { status: "ok", probe: "ready" });
    await app.close();
  });
});
