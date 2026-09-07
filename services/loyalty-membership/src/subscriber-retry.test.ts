import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";

import { livenessRegistry, type LivenessComponent } from "@trainticket/ts-kit";

import { createApp } from "./app.js";
import { startSubscriber } from "./bootstrap.js";
import { superviseSubscribe } from "./subscriber-retry.js";

/**
 * Regression tests for gap #1 of the 2026-09-06 outage class.
 *
 * `startSubscriber` used to swallow a failed `subscribe()` and return
 * `undefined`, leaving the process serving HTTP with zero event consumption --
 * permanently, silently, behind a green `/healthz`. Because no
 * `redis-consumer.*` component was ever registered on that path, the
 * never-healthy liveness guard correctly refused to fire, and a restart would
 * not have helped either: the failure is at subscribe time and simply recurs.
 *
 * These tests must FAIL if the background retry is removed. Each one below is
 * annotated with what it catches.
 */

/** A subscribed consumer, standing in for RedisStreamEventSubscriber. */
class FakeConsumer {
  closed = 0;
  constructor(readonly id: number) {}
  async close(): Promise<void> {
    this.closed += 1;
  }
}

/**
 * Deterministic supervisor harness.
 *
 * No real timers, and no free-running loop: each backoff `sleep` parks until
 * the test calls `release()`. That makes the retry schedule observable and the
 * attempt count exact, instead of racing an unbounded spin that would starve
 * the event loop.
 */
function harness(outcomes: readonly ("fail" | "ok")[], options: { failForever?: boolean } = {}) {
  const sleeps: number[] = [];
  const logs: Record<string, unknown>[] = [];
  const created: FakeConsumer[] = [];
  let attempt = 0;
  let wake: (() => void) | undefined;

  const supervisor = superviseSubscribe<FakeConsumer>({
    subscribe: async () => {
      const outcome = outcomes[attempt] ?? (options.failForever ? "fail" : "ok");
      attempt += 1;
      if (outcome === "fail") {
        throw new Error(`ECONNREFUSED 10.96.88.38:6379 (attempt ${attempt})`);
      }
      const consumer = new FakeConsumer(attempt);
      created.push(consumer);
      return consumer;
    },
    backoffMs: (n) => 100 * 2 ** Math.min(n - 1, 8),
    sleep: async (ms) => {
      sleeps.push(ms);
      await new Promise<void>((resolve) => {
        wake = resolve;
      });
    },
    log: (entry) => logs.push(entry),
  });

  /** Let exactly one parked backoff proceed, then wait for the next attempt. */
  const release = async (): Promise<void> => {
    const resume = wake;
    wake = undefined;
    resume?.();
    // Two macrotask turns: one for the retry attempt, one for its outcome.
    await new Promise((resolve) => setImmediate(resolve));
    await new Promise((resolve) => setImmediate(resolve));
  };

  /** Release backoffs until `predicate` holds or `limit` releases elapse. */
  const releaseUntil = async (predicate: () => boolean, limit = 60): Promise<void> => {
    for (let index = 0; index < limit && !predicate(); index += 1) {
      await release();
    }
  };

  return { supervisor, sleeps, logs, created, attempts: () => attempt, release, releaseUntil };
}

/**
 * Await a supervisor milestone with a bound, so a regression that never
 * reaches it fails loudly instead of hanging the suite.
 */
async function expectResolved(promise: Promise<void>, label: string): Promise<void> {
  const timeout = Symbol("timeout");
  const timer = new Promise<typeof timeout>((resolve) => {
    const handle = setTimeout(() => resolve(timeout), 2_000);
    handle.unref?.();
  });
  assert.notEqual(await Promise.race([promise.then(() => "resolved"), timer]), timeout, label);
}

describe("loyalty-membership subscribe failure is transient, not terminal", () => {
  const registered: LivenessComponent[] = [];

  afterEach(() => {
    for (const component of registered.splice(0)) {
      component.dispose();
    }
  });

  function register(name: string, gracePeriodMs: number, clock: () => number): LivenessComponent {
    const component = livenessRegistry.register(name, { gracePeriodMs, clock });
    registered.push(component);
    return component;
  }

  /**
   * DISCRIMINATOR: subscribe fails at boot, then succeeds on retry.
   * Without the retry, attempt 2 never happens and `state()` stays "retrying"
   * forever -- the permanent silent degradation this work exists to remove.
   */
  it("recovers consumption after a boot-time subscribe failure, with no restart", async () => {
    const { supervisor, created, sleeps, logs, releaseUntil } = harness(["fail", "fail", "ok"]);

    // Boot completes even though Redis was down: HTTP is up, retry in flight.
    await supervisor.settled();
    assert.equal(supervisor.state(), "retrying", "first attempt failed, so consumption is not live yet");

    // The retry lands without any process restart.
    await releaseUntil(() => supervisor.state() === "consuming");
    await expectResolved(supervisor.consuming(), "the background retry must start consumption");
    assert.equal(supervisor.state(), "consuming", "the background retry must start consumption");
    assert.equal(supervisor.attempts(), 3, "it must keep retrying until subscribe succeeds");
    assert.equal(created.length, 1, "exactly one consumer ends up attached");

    // Capped exponential backoff between attempts, per connectRedisWithRetry.
    assert.deepEqual(sleeps, [100, 200], "backoff must grow exponentially between attempts");
    assert.ok(
      logs.some((entry) => String(entry.message).includes("resumed without a restart")),
      "recovery must be logged as a recovery",
    );

    await supervisor.close();
    assert.equal(created[0].closed, 1, "shutdown closes the attached consumer");
  });

  /**
   * DISCRIMINATOR: retries continue indefinitely with a CAPPED delay.
   * Without the retry there is exactly one attempt. A retry that gave up after
   * N tries, or whose backoff grew unbounded, also fails here.
   */
  it("retries indefinitely with capped backoff while Redis stays down", async () => {
    const { supervisor, sleeps, attempts, releaseUntil } = harness([], { failForever: true });
    await supervisor.settled();

    await releaseUntil(() => attempts() >= 30, 40);

    assert.ok(attempts() >= 30, `must still be retrying after many failures, saw ${attempts()} attempts`);
    assert.equal(supervisor.state(), "retrying");
    // 100ms doubling, capped at 100 * 2^8 = 25600ms, and never beyond it.
    assert.equal(Math.max(...sleeps), 25_600, "backoff must be capped, not unbounded");
    assert.deepEqual(sleeps.slice(0, 5), [100, 200, 400, 800, 1_600], "backoff must grow exponentially");
    assert.ok(sleeps.length >= 29, "every failed attempt must be followed by a backoff");

    await supervisor.close();
  });

  /**
   * DISCRIMINATOR: the never-healthy guard must HOLD during the retry window.
   * If someone "fixed" this gap by weakening the guard (e.g. registering an
   * unhealthy consumer component up front, or letting a never-healthy
   * component expire), this test goes red -- and a cluster-wide restart storm
   * across seven services becomes possible again.
   */
  it("never fails liveness while subscribe is still being retried", async () => {
    let now = 1_000;
    const { supervisor } = harness([], { failForever: true });
    await supervisor.settled();

    // A component that never became healthy: the publisher client that was
    // created but could never connect, which is the real state during an
    // outage at boot. Well past its grace period.
    const publisher = register("publisher.connection", 1_000, () => now);
    publisher.markUnhealthy("not yet connected");
    now += 3_600_000;

    const app = createApp({ eventConsumption: () => supervisor.state() });

    assert.equal(supervisor.state(), "retrying");
    assert.equal(
      (await app.inject("/healthz")).statusCode,
      200,
      "liveness must not fire during the retry window; startup is readiness' job and a restart cannot fix a down dependency",
    );

    await supervisor.close();
  });

  /**
   * DISCRIMINATOR: the /readyz decision, asserted exactly.
   * 200 (so the working HTTP API keeps its only endpoint on a replicas:1
   * Deployment) plus an explicit eventConsumption:"retrying" in the body.
   * Flipping readiness to 503 during the retry window fails this test.
   */
  it("reports readiness 200 with eventConsumption retrying while subscribe is retried", async () => {
    const { supervisor } = harness([], { failForever: true });
    await supervisor.settled();

    const app = createApp({ eventConsumption: () => supervisor.state() });
    const readyz = await app.inject("/readyz");

    assert.equal(readyz.statusCode, 200, "HTTP still works, and 503 would delete the only Service endpoint");
    assert.deepEqual(readyz.json(), { status: "ok", probe: "ready", eventConsumption: "retrying" });
    assert.deepEqual((await app.inject("/ready")).json(), { status: "ok", probe: "ready", eventConsumption: "retrying" });

    await supervisor.close();
  });

  it("reports eventConsumption consuming once subscribe succeeds", async () => {
    const { supervisor, releaseUntil } = harness(["fail", "ok"]);
    await releaseUntil(() => supervisor.state() === "consuming");
    await expectResolved(supervisor.consuming(), "the retry must reach consuming");

    const app = createApp({ eventConsumption: () => supervisor.state() });
    const readyz = await app.inject("/readyz");

    assert.equal(readyz.statusCode, 200);
    assert.deepEqual(readyz.json(), { status: "ok", probe: "ready", eventConsumption: "consuming" });

    await supervisor.close();
  });

  /**
   * DISCRIMINATOR: a LATER wedge must still be caught.
   * The retry must not shield the process from liveness once consumption has
   * actually started. If the retry were implemented by suppressing or
   * re-creating the consumer component after success, this would go red.
   */
  it("still fails liveness when a successfully subscribed consumer wedges later", async () => {
    let now = 1_000;
    const { supervisor, releaseUntil } = harness(["fail", "ok"]);
    await releaseUntil(() => supervisor.state() === "consuming");
    await expectResolved(supervisor.consuming(), "the retry must reach consuming before the wedge");
    assert.equal(supervisor.state(), "consuming");

    // ts-kit registers and marks this healthy inside a successful subscribe().
    const consumer = register("redis-consumer.loyalty-membership", 300_000, () => now);
    consumer.markHealthy();

    const app = createApp({ eventConsumption: () => supervisor.state() });
    assert.equal((await app.inject("/healthz")).statusCode, 200);

    // Now it wedges, permanently.
    consumer.markUnhealthy("poll failing: Connection is closed");
    now += 299_000;
    assert.equal((await app.inject("/healthz")).statusCode, 200, "still inside the grace period");

    now += 2_000;
    assert.equal(
      (await app.inject("/healthz")).statusCode,
      503,
      "once a consumer has worked, a permanent wedge must still restart the pod",
    );

    await supervisor.close();
  });

  /**
   * DISCRIMINATOR: a fresh subscriber per attempt.
   * A RedisEventSubscriber whose subscribe() rejected is permanently
   * `stopped`; re-subscribing that instance registers a HEALTHY consumer
   * component whose supervised loops exit immediately -- a green probe over
   * silent non-consumption, i.e. the original outage. Reusing the instance
   * would fail this test.
   */
  it("builds a fresh consumer for each subscribe attempt", async () => {
    const attemptIds: number[] = [];
    let attempt = 0;
    let wake: (() => void) | undefined;
    const supervisor = superviseSubscribe<FakeConsumer>({
      subscribe: async () => {
        attempt += 1;
        attemptIds.push(attempt);
        if (attempt < 3) {
          throw new Error("subscribe failed");
        }
        return new FakeConsumer(attempt);
      },
      backoffMs: () => 0,
      sleep: async () => {
        await new Promise<void>((resolve) => {
          wake = resolve;
        });
      },
      log: () => {},
    });

    for (let index = 0; index < 5 && attempt < 3; index += 1) {
      const resume = wake;
      wake = undefined;
      resume?.();
      await new Promise((resolve) => setImmediate(resolve));
      await new Promise((resolve) => setImmediate(resolve));
    }

    await expectResolved(supervisor.consuming(), "each attempt must construct its own consumer");
    assert.deepEqual(attemptIds, [1, 2, 3], "each attempt must construct its own consumer");
    await supervisor.close();
  });

  it("stops retrying on shutdown instead of leaking a backoff loop", async () => {
    const { supervisor, attempts, release } = harness([], { failForever: true });
    await supervisor.settled();
    assert.equal(attempts(), 1);

    // close() while a backoff is parked: the loop must exit, not attempt again.
    const closed = supervisor.close();
    await release();
    await closed;

    const seen = attempts();
    for (let tick = 0; tick < 20; tick += 1) {
      await new Promise((resolve) => setImmediate(resolve));
    }
    assert.equal(attempts(), seen, "close() must halt the retry loop");
  });

  /**
   * Guards the wiring, not just the helper: bootstrap's startSubscriber must
   * return a live supervisor (not `undefined`) whose state is observable, so
   * the retry is actually reachable in production.
   */
  it("wires startSubscriber to a supervisor that exposes consumption state", async () => {
    let attempt = 0;
    let wake: (() => void) | undefined;
    const supervisor = startSubscriber<{ close: () => Promise<void> }>(undefined as never, {
      subscribe: async () => {
        attempt += 1;
        if (attempt === 1) {
          throw new Error("ECONNREFUSED");
        }
        return { close: async () => {} };
      },
      backoffMs: () => 0,
      sleep: async () => {
        await new Promise<void>((resolve) => {
          wake = resolve;
        });
      },
      log: () => {},
    });

    assert.ok(supervisor, "startSubscriber must return a supervisor when a subscribe override is given");
    await supervisor.settled();
    assert.equal(supervisor.state(), "retrying", "a failed first attempt must not report consuming");

    const resume = wake;
    resume?.();
    await expectResolved(supervisor.consuming(), "bootstrap's supervisor must retry to success");
    assert.equal(supervisor.state(), "consuming", "bootstrap's supervisor must retry to success");
    assert.equal(attempt, 2, "bootstrap's supervisor must make a second attempt");
    await supervisor.close();
  });

  it("returns no supervisor when REDIS_URL is unset, as before", () => {
    const previous = process.env.REDIS_URL;
    delete process.env.REDIS_URL;
    try {
      assert.equal(startSubscriber(undefined as never), undefined);
    } finally {
      if (previous !== undefined) process.env.REDIS_URL = previous;
    }
  });
});
