import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { livenessProbe, livenessRegistry, livenessState, registerLivenessComponent } from "./liveness.js";
import { migrationsAwareReadiness, superviseMigrations, type MigrationSupervisor } from "./storage.js";

/**
 * A migration that failed once at startup used to be swallowed
 * (`catch { console.error(...) }`) and never retried, so `isReady` stayed false
 * for the life of the process and `/readyz` answered 503 forever while the pod
 * consumed events perfectly well. These tests pin the three behaviours that
 * distinguish the fix from the defect:
 *
 *  1. fails then succeeds -> becomes ready, no restart needed;
 *  2. never succeeds      -> stays not-ready AND does not trip liveness;
 *  3. succeeds first time -> behaves exactly as `await apply()` did.
 */

const noSleep = async (): Promise<void> => undefined;

/**
 * Await a promise, failing the test rather than hanging the run if it never
 * settles. Without this, deleting the retry would make "recovers on retry" hang
 * forever on `applied()` instead of going red, which is useless as a
 * discriminator.
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

/** A supervisor whose apply() resolves/rejects on demand, with no real timers. */
function supervisorOver(outcomes: readonly (Error | "ok")[], overrides: Parameters<typeof superviseMigrations>[1] = {}): {
  supervisor: MigrationSupervisor;
  applyCalls: () => number;
  logs: Record<string, unknown>[];
} {
  let calls = 0;
  const logs: Record<string, unknown>[] = [];
  const supervisor = superviseMigrations(undefined as never, {
    apply: async () => {
      const outcome = outcomes[Math.min(calls, outcomes.length - 1)];
      calls += 1;
      if (outcome !== "ok") {
        throw outcome;
      }
    },
    backoffMs: () => 0,
    sleep: noSleep,
    log: (entry) => logs.push(entry),
    ...overrides,
  });
  return { supervisor, applyCalls: () => calls, logs };
}

describe("superviseMigrations", () => {
  it("succeeds on the first attempt with the same observable result as a plain apply()", async () => {
    const { supervisor, applyCalls, logs } = supervisorOver(["ok"]);

    await supervisor.settled();

    assert.equal(supervisor.state(), "applied");
    assert.equal(supervisor.isReady(), true);
    assert.equal(supervisor.failure(), undefined);
    assert.equal(supervisor.attempts(), 1);
    assert.equal(applyCalls(), 1);
    // The happy path must stay silent: no retry noise for a boot that worked.
    assert.deepEqual(logs, []);
    assert.deepEqual(supervisor.detail(), { schema: "applied", attempts: 1 });
    await supervisor.applied();
    await supervisor.stop();
  });

  it("recovers without a restart: a migration that fails at boot and succeeds on retry reaches ready", async () => {
    const { supervisor, applyCalls, logs } = supervisorOver([
      new Error("connection refused"),
      new Error("the database system is starting up"),
      "ok",
    ]);

    // The FIRST attempt settling must not be mistaken for readiness: this is
    // the moment bootstrap resumes and starts serving HTTP.
    await supervisor.settled();
    assert.equal(supervisor.isReady(), false, "must not be ready while the first attempt has failed");
    assert.equal(supervisor.state(), "migrating");

    await within(supervisor.applied(), "migrations never became ready after retrying");

    assert.equal(supervisor.isReady(), true, "the retry must clear readiness without a process restart");
    assert.equal(supervisor.state(), "applied");
    assert.equal(supervisor.failure(), undefined);
    assert.equal(applyCalls(), 3);
    assert.ok(supervisor.attempts() >= 3);

    // Each failure is diagnosable, and the recovery is announced.
    const failures = logs.filter((entry) => String(entry.message).includes("will retry in background"));
    assert.equal(failures.length, 2);
    assert.equal(failures[0]?.dependency, "postgres");
    assert.deepEqual(failures[0]?.error, { name: "Error", message: "connection refused" });
    const recovery = logs.find((entry) => String(entry.message).includes("without a restart"));
    assert.ok(recovery, "recovery must be logged so the operator sees the pod self-healed");
    await supervisor.stop();
  });

  it("keeps retrying and stays not-ready while migrations never succeed, and never trips liveness", async () => {
    livenessRegistry.clear();
    // A component that HAS worked before is the only thing liveness may fail.
    // Register one and keep it healthy to prove the probe is otherwise live.
    const healthy = registerLivenessComponent("redis-consumer.test", { gracePeriodMs: 1 });
    healthy.markHealthy();

    let attemptsSeen = 0;
    const { supervisor } = supervisorOver([new Error("relation \"schema_migrations\" does not exist")], {
      apply: async () => {
        attemptsSeen += 1;
        throw new Error("connection refused");
      },
    });

    await supervisor.settled();
    // Let the retry loop spin a while; backoff and sleep are both zero here.
    for (let tick = 0; tick < 50; tick += 1) {
      await Promise.resolve();
    }

    assert.ok(attemptsSeen > 1, `expected repeated retries, saw ${attemptsSeen}`);
    assert.equal(supervisor.isReady(), false, "a never-succeeding migration must keep the service not-ready");
    assert.equal(supervisor.state(), "migrating");
    assert.ok(supervisor.failure() instanceof Error);

    // The whole point of the never-healthy guard: a boot-time database outage
    // must NOT restart-storm the fleet, because a restart cannot fix it.
    const probe = livenessProbe();
    assert.equal(probe.statusCode, 200, "migration retries must not fail liveness");
    assert.equal(probe.live, true);
    assert.deepEqual(probe.failed, []);
    assert.equal(
      livenessState().components.some((component) => component.name.includes("migration")),
      false,
      "the supervisor must not register a component that could expire",
    );

    await supervisor.stop();
    healthy.dispose();
    livenessRegistry.clear();
  });

  it("escalates to console.error once it has been stuck past the warning interval", async () => {
    const errors: unknown[] = [];
    const originalConsoleError = console.error;
    console.error = (value?: unknown) => {
      errors.push(value);
    };

    let now = 0;
    try {
      const { supervisor, logs } = supervisorOver([new Error("connection refused")], {
        clock: () => now,
        stuckAfterMs: 1_000,
        sleep: async () => {
          now += 400;
        },
      });

      await supervisor.settled();
      for (let tick = 0; tick < 40; tick += 1) {
        await Promise.resolve();
      }
      await supervisor.stop();

      assert.ok(logs.length > 0, "ordinary retries stay at warn level");
      assert.ok(errors.length > 0, "a service stuck migrating must escalate in its logs");
      const escalated = errors[0] as Record<string, unknown>;
      assert.match(String(escalated.message), /have been failing for \d+s/u);
      assert.match(String(escalated.message), /readyz is 503/u);
      assert.match(String(escalated.message), /Liveness stays green on purpose/u);
      assert.equal(escalated.dependency, "postgres");
    } finally {
      console.error = originalConsoleError;
    }
  });

  it("reports the retry state for /readyz bodies so it is diagnosable without the logs", async () => {
    let now = 0;
    const { supervisor } = supervisorOver([new Error("connection refused")], {
      clock: () => now,
      sleep: async () => {
        now += 250;
      },
    });

    await supervisor.settled();
    for (let tick = 0; tick < 20; tick += 1) {
      await Promise.resolve();
    }

    const detail = supervisor.detail();
    assert.equal(detail.schema, "migrating");
    assert.ok((detail.attempts ?? 0) >= 1);
    assert.ok((detail.retryingForMs ?? 0) > 0);
    assert.equal(detail.lastError, "connection refused");
    await supervisor.stop();
  });

  it("stops promptly during a backoff instead of waiting it out", async () => {
    let interrupted = false;
    const { supervisor } = supervisorOver([new Error("connection refused")], {
      backoffMs: () => 30_000,
      sleep: (milliseconds) => new Promise((resolve) => {
        // Never resolves on its own within the test; only the stop() interrupt
        // can unblock the loop.
        const timer = setTimeout(resolve, milliseconds);
        timer.unref?.();
        interrupted = true;
      }),
    });

    await supervisor.settled();
    await supervisor.stop();

    assert.equal(interrupted, true);
    assert.equal(supervisor.isReady(), false);
  });
});

describe("migrationsAwareReadiness", () => {
  /** A pool whose connect/query always succeed: the database is reachable. */
  class ReachablePool {
    public connects = 0;

    async connect(): Promise<{ query: () => Promise<unknown>; release: () => void }> {
      this.connects += 1;
      return { query: async () => ({ rows: [], rowCount: 0 }), release: () => {} };
    }
  }

  it("reports not-ready while migrations are still being applied, even though the database is reachable", async () => {
    const pool = new ReachablePool();
    const ready = migrationsAwareReadiness({ isReady: () => false }, pool as never);

    assert.equal(await ready(), false);
    // Short-circuits before touching the pool: no connection per probe while
    // the schema is missing.
    assert.equal(pool.connects, 0);
  });

  it("reports ready once migrations have applied and the database answers", async () => {
    const pool = new ReachablePool();
    const ready = migrationsAwareReadiness({ isReady: () => true }, pool as never);

    assert.equal(await ready(), true);
    assert.equal(pool.connects, 1);
  });

  it("reports not-ready when migrations applied but the database has since gone away", async () => {
    const unreachable = {
      connect: async () => {
        throw new Error("connect ECONNREFUSED");
      },
    };
    const ready = migrationsAwareReadiness({ isReady: () => true }, unreachable as never);

    assert.equal(await ready(), false);
  });
});
