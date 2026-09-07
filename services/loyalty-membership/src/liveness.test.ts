import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";

import { livenessRegistry, type LivenessComponent } from "@trainticket/ts-kit";

import { createApp } from "./app.js";

/**
 * Regression test for the 2026-09-06 outage.
 *
 * A 10-second Redis restart wedged the `account` pod for 20 hours because
 * `/healthz` returned a static 200: readiness went 503 and Kubernetes pulled
 * the pod out of the Service endpoints, but liveness passed forever so kubelet
 * never restarted the dead process. This service served the same static
 * liveness response and was capable of wedging identically.
 *
 * Liveness must therefore fail when a dependency has been dead long enough
 * that only a restart can help -- and must NOT fail on a transient blip or
 * during startup, because seven services flapping at once would turn a
 * dependency blip into a cluster-wide restart storm.
 *
 * loyalty-membership additionally starts its subscriber only when REDIS_URL is
 * set and swallows a failed subscription, so it can legitimately run with no
 * consumer component registered at all. The "never healthy" case below is the
 * guard that keeps that degraded-but-deliberate mode from restarting the pod.
 */
describe("loyalty-membership liveness reflects whether the service can still do work", () => {
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
    const consumer = register("redis-consumer.loyalty-membership", 300_000, () => now);
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

  it("never accumulates unhealth across repeated blips", async () => {
    let now = 1_000;
    const consumer = register("redis-consumer.loyalty-membership", 300_000, () => now);
    consumer.markHealthy();

    const app = createApp();

    // Twenty 60s outages, each recovered: 1200s of unhealth in total, but
    // never 300s of *continuous* unhealth. The clock resets on every recovery,
    // so repeated blips must never accumulate into a restart.
    for (let blip = 0; blip < 20; blip += 1) {
      consumer.markUnhealthy("poll failing: Connection is closed");
      now += 60_000;
      assert.equal((await app.inject("/healthz")).statusCode, 200, `blip ${blip} must not fail liveness`);
      consumer.markHealthy();
      now += 1_000;
    }
  });

  it("fails 503 once the Redis consumer has been dead past the grace period", async () => {
    let now = 1_000;
    const consumer = register("redis-consumer.loyalty-membership", 300_000, () => now);
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

  it("fails 503 when the outbox relay is the wedged component", async () => {
    let now = 1_000;
    const relay = register("loyalty-membership-outbox-relay", 300_000, () => now);
    relay.markHealthy();

    const app = createApp();
    relay.markUnhealthy("outbox relay failing: Connection is closed");
    now += 301_000;

    assert.equal((await app.inject("/healthz")).statusCode, 503, "a relay that stopped publishing must fail liveness");
  });

  it("recovers to 200 if the consumer comes back after the grace period elapsed", async () => {
    let now = 1_000;
    const consumer = register("redis-consumer.loyalty-membership", 60_000, () => now);
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
    // loyalty-membership boots without a subscriber when REDIS_URL is unset;
    // its lazily created publisher client then never connects. That client
    // must never be able to restart an otherwise healthy process.
    const publisher = register("publisher.connection", 1_000, () => now);
    publisher.markUnhealthy("not yet connected");
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
    const consumer = register("redis-consumer.loyalty-membership", 1_000, () => now);
    consumer.markHealthy();
    consumer.markUnhealthy("dead");
    now += 10_000;

    const app = createApp();
    // No storage configured, so readiness is unaffected by the dead consumer.
    assert.equal((await app.inject("/readyz")).statusCode, 200);
    assert.equal((await app.inject("/healthz")).statusCode, 503);
  });
});
