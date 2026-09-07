import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";

import { livenessRegistry, type LivenessComponent } from "@trainticket/ts-kit";

import { createApp } from "./app.js";

/**
 * Regression test for the 2026-09-06 outage.
 *
 * The account pod's Redis client never reconnected after the shared Redis pod
 * was OOMKilled. Readiness went 503 and Kubernetes pulled the pod out of the
 * Service endpoints, but `/healthz` returned 200 forever, so kubelet never
 * restarted it. The pod stayed wedged for 17+ hours with no self-healing path.
 *
 * Liveness must therefore fail when a dependency has been dead long enough
 * that only a restart can help -- and must NOT fail on transient blips.
 */
describe("account liveness reflects whether the service can still do work", () => {
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

  it("returns 200 while everything is healthy", async () => {
    const app = createApp();
    const healthz = await app.inject("/healthz");

    assert.equal(healthz.statusCode, 200);
    assert.deepEqual(healthz.json(), { status: "ok", probe: "live" });
  });

  it("stays 200 while the Redis consumer is briefly disconnected", async () => {
    let now = 1_000;
    const consumer = register("redis-consumer.account", 300_000, () => now);
    consumer.markHealthy();

    const app = createApp();

    // A 30s blip -- longer than a real reconnect, far inside the grace period.
    consumer.markUnhealthy("connect ECONNREFUSED 10.96.88.38:6379");
    now += 30_000;

    const healthz = await app.inject("/healthz");
    assert.equal(healthz.statusCode, 200, "a transient Redis blip must never restart the pod");
    assert.deepEqual(healthz.json(), { status: "ok", probe: "live" });

    // Reconnected: still healthy.
    consumer.markHealthy();
    assert.equal((await app.inject("/healthz")).statusCode, 200);
  });

  it("fails 503 once the Redis consumer has been dead past the grace period", async () => {
    let now = 1_000;
    const consumer = register("redis-consumer.account", 300_000, () => now);
    consumer.markHealthy();

    const app = createApp();
    consumer.markUnhealthy("poll failing: Connection is closed");

    now += 299_000;
    assert.equal((await app.inject("/healthz")).statusCode, 200, "still inside the grace period");

    now += 2_000;
    const healthz = await app.inject("/healthz");
    assert.equal(healthz.statusCode, 503, "a permanently dead consumer must fail liveness");
    assert.deepEqual(healthz.json(), { status: "unhealthy", probe: "live" });

    // /live and /livez must agree, since they are the same signal.
    assert.equal((await app.inject("/live")).statusCode, 503);
    assert.equal((await app.inject("/livez")).statusCode, 503);
  });

  it("recovers to 200 if the consumer comes back after the grace period elapsed", async () => {
    let now = 1_000;
    const consumer = register("redis-consumer.account", 60_000, () => now);
    consumer.markHealthy();
    consumer.markUnhealthy("poll failing: Connection is closed");
    now += 120_000;

    const app = createApp();
    assert.equal((await app.inject("/healthz")).statusCode, 503);

    consumer.markHealthy();
    assert.equal((await app.inject("/healthz")).statusCode, 200, "liveness must clear once work resumes");
  });

  it("never fails liveness for a dependency that was never healthy", async () => {
    let now = 1_000;
    const consumer = register("redis-consumer.never-up", 1_000, () => now);
    consumer.markUnhealthy("not yet connected");
    now += 600_000;

    const app = createApp();
    assert.equal(
      (await app.inject("/healthz")).statusCode,
      200,
      "a component that never worked must not restart the pod; readiness covers startup",
    );
  });

  it("keeps readiness independent of liveness", async () => {
    let now = 1_000;
    const consumer = register("redis-consumer.account", 1_000, () => now);
    consumer.markHealthy();
    consumer.markUnhealthy("dead");
    now += 10_000;

    const app = createApp();
    // No storage configured, so readiness is unaffected by the dead consumer.
    assert.equal((await app.inject("/readyz")).statusCode, 200);
    assert.equal((await app.inject("/healthz")).statusCode, 503);
  });
});
