import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";

import { livenessRegistry, type LivenessComponent } from "@trainticket/ts-kit";

import { createApp } from "../src/app.js";
import { superviseWaitlistSubscribe, type WaitlistSubscribeAttempt } from "../src/subscriber-retry.js";

/**
 * Regression tests for gap #2 of the 2026-09-06 outage class.
 *
 * waitlist's bootstrap used to do:
 *
 *   subscription.catch((error) => { app.log.error(...); abortController.abort(); });
 *
 * i.e. log-and-continue, with an abort that made the loss deliberate and
 * permanent. Its Redis client is already connected by that point (via
 * `createRedisMessagingAdapters` -> `connectRedisWithRetry`), so
 * `subscriber.connection` stayed HEALTHY while consumption was dead, and no
 * `redis-consumer.waitlist` component was ever registered -- nothing in the
 * liveness registry could fail. Green `/healthz`, zero events, forever.
 *
 * These tests must FAIL if the background retry is removed.
 */

class FakeAttempt implements WaitlistSubscribeAttempt {
  closed = 0;
  async close(): Promise<void> {
    this.closed += 1;
  }
}

/**
 * Deterministic harness: each backoff parks until the test releases it, so the
 * attempt count and backoff schedule are exact rather than raced.
 */
function harness(outcomes: readonly ("fail" | "ok")[], options: { failForever?: boolean } = {}) {
  const sleeps: number[] = [];
  const logs: Record<string, unknown>[] = [];
  const created: FakeAttempt[] = [];
  let attempt = 0;
  let wake: (() => void) | undefined;

  const supervisor = superviseWaitlistSubscribe({
    subscribe: async () => {
      const outcome = outcomes[attempt] ?? (options.failForever ? "fail" : "ok");
      attempt += 1;
      if (outcome === "fail") {
        throw new Error(`SubscribeFailed: NOGROUP / ECONNREFUSED (attempt ${attempt})`);
      }
      const subscribed = new FakeAttempt();
      created.push(subscribed);
      return subscribed;
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

  const release = async (): Promise<void> => {
    const resume = wake;
    wake = undefined;
    resume?.();
    await new Promise((resolve) => setImmediate(resolve));
    await new Promise((resolve) => setImmediate(resolve));
  };
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

describe("waitlist subscribe failure is retried instead of logged and abandoned", () => {
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
   * DISCRIMINATOR: subscribe rejects at boot, then succeeds on retry.
   * With the old log-and-continue there is exactly one attempt and consumption
   * never starts.
   */
  it("recovers consumption after a rejected subscribe, with no restart", async () => {
    const { supervisor, created, sleeps, logs, releaseUntil } = harness(["fail", "fail", "ok"]);

    await supervisor.settled();
    assert.equal(supervisor.state(), "retrying", "the rejected promise must not report consuming");

    await releaseUntil(() => supervisor.state() === "consuming");
    await expectResolved(supervisor.consuming(), "the background retry must start consumption");
    assert.equal(supervisor.state(), "consuming", "the background retry must start consumption");
    assert.equal(supervisor.attempts(), 3, "it must keep retrying until subscribe resolves");
    assert.equal(created.length, 1);
    assert.deepEqual(sleeps, [100, 200], "capped exponential backoff between attempts");
    assert.ok(
      logs.some((entry) => String(entry.message).includes("resumed without a restart")),
      "recovery must be logged as a recovery",
    );

    await supervisor.close();
    assert.equal(created[0].closed, 1, "shutdown closes the attached subscription");
  });

  /**
   * DISCRIMINATOR: retries are indefinite and the backoff is capped.
   */
  it("retries indefinitely with capped backoff while Redis stays unavailable", async () => {
    const { supervisor, sleeps, attempts, releaseUntil } = harness([], { failForever: true });
    await supervisor.settled();

    await releaseUntil(() => attempts() >= 30, 40);

    assert.ok(attempts() >= 30, `must still be retrying, saw ${attempts()} attempts`);
    assert.equal(supervisor.state(), "retrying");
    assert.equal(Math.max(...sleeps), 25_600, "backoff must be capped, not unbounded");
    assert.deepEqual(sleeps.slice(0, 5), [100, 200, 400, 800, 1_600]);

    await supervisor.close();
  });

  /**
   * DISCRIMINATOR: the never-healthy guard must HOLD during the retry window.
   * Note the waitlist-specific trap: the Redis CLIENT is healthy here (it
   * connected fine), so this models the real post-connect subscribe failure.
   * Weakening the guard to make liveness fire during the retry breaks this.
   */
  it("never fails liveness while subscribe is being retried, even with a healthy client", async () => {
    let now = 1_000;
    const { supervisor } = harness([], { failForever: true });
    await supervisor.settled();

    // The connection component is healthy: connectRedisWithRetry succeeded.
    const connection = register("waitlist-subscriber.connection", 300_000, () => now);
    connection.markHealthy();
    // And a never-healthy component from a failed attempt, well past its grace.
    const neverUp = register("redis-consumer.never-subscribed", 1_000, () => now);
    neverUp.markUnhealthy("not yet connected");
    now += 3_600_000;

    const app = createApp({ eventConsumption: () => supervisor.state() });

    assert.equal(supervisor.state(), "retrying");
    assert.equal(
      (await app.inject("/healthz")).statusCode,
      200,
      "liveness must not fire during the retry window; a restart cannot fix a down dependency",
    );
    assert.equal((await app.inject("/livez")).statusCode, 200);

    await supervisor.close();
  });

  /**
   * DISCRIMINATOR: the /readyz decision, asserted exactly.
   * 200 plus eventConsumption:"retrying". Flipping to 503 during the retry
   * window would delete waitlist's only Service endpoint and break the
   * still-working join/cancel/accept API; this test pins that choice.
   */
  it("reports readiness 200 with eventConsumption retrying while subscribe is retried", async () => {
    const { supervisor } = harness([], { failForever: true });
    await supervisor.settled();

    const app = createApp({ eventConsumption: () => supervisor.state() });
    const readyz = await app.inject("/readyz");

    assert.equal(readyz.statusCode, 200, "HTTP still works; 503 would remove the only endpoint");
    assert.deepEqual(readyz.json(), { status: "ok", probe: "ready", eventConsumption: "retrying" });
    assert.deepEqual((await app.inject("/ready")).json(), { status: "ok", probe: "ready", eventConsumption: "retrying" });

    await supervisor.close();
  });

  it("reports eventConsumption consuming once subscribe succeeds", async () => {
    const { supervisor, releaseUntil } = harness(["fail", "ok"]);
    await releaseUntil(() => supervisor.state() === "consuming");
    await expectResolved(supervisor.consuming(), "the retry must reach consuming");

    const app = createApp({ eventConsumption: () => supervisor.state() });
    assert.deepEqual((await app.inject("/readyz")).json(), { status: "ok", probe: "ready", eventConsumption: "consuming" });

    await supervisor.close();
  });

  /**
   * DISCRIMINATOR: readiness still reports Postgres unreadiness as 503.
   * The retry must not have made readiness unconditionally 200.
   */
  it("still fails readiness when storage is not ready", async () => {
    const { supervisor } = harness([], { failForever: true });
    await supervisor.settled();

    const app = createApp({
      storage: { ready: () => false, runCommand: async () => undefined as never },
      eventConsumption: () => supervisor.state(),
    });
    const readyz = await app.inject("/readyz");

    assert.equal(readyz.statusCode, 503, "a broken database does break the HTTP API");
    assert.deepEqual(readyz.json(), { status: "not_ready", probe: "ready", eventConsumption: "retrying" });

    await supervisor.close();
  });

  /**
   * DISCRIMINATOR: a LATER wedge must still fire liveness.
   * The retry must not shield a consumer that worked and then died.
   */
  it("still fails liveness when a successfully subscribed consumer wedges later", async () => {
    let now = 1_000;
    const { supervisor, releaseUntil } = harness(["fail", "ok"]);
    await releaseUntil(() => supervisor.state() === "consuming");
    await expectResolved(supervisor.consuming(), "the retry must reach consuming before the wedge");

    // ts-kit registers and marks this healthy inside a successful subscribe().
    const consumer = register("redis-consumer.waitlist", 300_000, () => now);
    consumer.markHealthy();

    const app = createApp({ eventConsumption: () => supervisor.state() });
    assert.equal((await app.inject("/healthz")).statusCode, 200);

    consumer.markUnhealthy("poll failing: Connection is closed");
    now += 299_000;
    assert.equal((await app.inject("/healthz")).statusCode, 200, "still inside the grace period");

    now += 2_000;
    assert.equal(
      (await app.inject("/healthz")).statusCode,
      503,
      "once a consumer has worked, a permanent wedge must still restart the pod",
    );
    // Readiness stays 200: the HTTP API is fine, liveness is the right signal.
    assert.equal((await app.inject("/readyz")).statusCode, 200);

    await supervisor.close();
  });

  it("keeps the default readiness body shape when no consumption reporter is wired", async () => {
    const app = createApp();
    const readyz = await app.inject("/readyz");

    assert.equal(readyz.statusCode, 200);
    assert.deepEqual(readyz.json(), { status: "ok", probe: "ready" }, "existing clients must see an unchanged body");
  });

  it("stops retrying on shutdown instead of leaking a backoff loop", async () => {
    const { supervisor, attempts, release } = harness([], { failForever: true });
    await supervisor.settled();
    assert.equal(attempts(), 1);

    const closed = supervisor.close();
    await release();
    await closed;

    const seen = attempts();
    for (let tick = 0; tick < 20; tick += 1) {
      await new Promise((resolve) => setImmediate(resolve));
    }
    assert.equal(attempts(), seen, "close() must halt the retry loop");
  });

  it("closes a subscription that lands after shutdown began", async () => {
    const landed = new FakeAttempt();
    let resolveSubscribe: ((value: WaitlistSubscribeAttempt) => void) | undefined;
    const supervisor = superviseWaitlistSubscribe({
      subscribe: () => new Promise<WaitlistSubscribeAttempt>((resolve) => {
        resolveSubscribe = resolve;
      }),
      backoffMs: () => 0,
      sleep: async () => {},
      log: () => {},
    });

    const closed = supervisor.close();
    resolveSubscribe?.(landed);
    await closed;

    assert.equal(landed.closed, 1, "a subscription that races shutdown must not be leaked");
    assert.equal(supervisor.state(), "retrying", "a post-shutdown landing must not report consuming");
  });
});
