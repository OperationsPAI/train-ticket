import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { describe, it } from "node:test";

import {
  DEFAULT_LIVENESS_GRACE_MS,
  LivenessRegistry,
  OutboxRelay,
  createRedisClient,
  isMissingGroupError,
  livenessGraceMs,
  livenessState,
  redisClientOptions,
  redisRetryStrategy,
} from "./index.js";
import { RedisEventSubscriber, createEventEnvelope, type EventEnvelope } from "./messaging.js";

/**
 * Regression tests for the 2026-09-06 outage: the shared Redis pod was
 * OOMKilled, the account service's ioredis client never reconnected, the
 * stream-consumer loop died silently, and `/healthz` returned 200 forever so
 * kubelet never restarted the wedged pod.
 */

const NOGROUP_ERROR = "NOGROUP No such key 'events:account' or consumer group 'account' in XREADGROUP with GROUP option";

function envelope(eventType = "AccountCreated"): EventEnvelope {
  return createEventEnvelope({ eventType, producer: "account", payload: {} });
}

function entryFor(id: string, message: EventEnvelope): [string, string[]] {
  return [id, ["envelope", JSON.stringify(message)]];
}

/**
 * A Redis double that models a real restart: it drops the connection, fails
 * reads while down, forgets its consumer groups, and emits ioredis' real
 * lifecycle events ("close", "ready") so subscriber reconnect logic runs.
 */
class RestartableFakeRedis extends EventEmitter {
  status = "ready";
  public readonly createdGroups: string[] = [];
  public readonly acked: string[] = [];
  private down = false;
  private groups = new Set<string>();
  private queue: Array<[string, [string, string[]][]]> = [];

  async connect(): Promise<void> {
    this.status = "ready";
  }

  disconnect(): void {
    this.status = "end";
  }

  /** Simulate the Redis pod dying: reads fail and the socket closes. */
  goDown(): void {
    this.down = true;
    this.status = "close";
    this.emit("close");
  }

  /**
   * Simulate the Redis pod coming back. A restarted Redis is EMPTY: streams
   * and consumer groups are gone by design in this system (Redis is
   * transport-only; unpublished work replays from the Postgres outbox).
   */
  comeBackEmpty(): void {
    this.down = false;
    this.groups.clear();
    this.status = "ready";
    this.emit("ready");
  }

  enqueue(stream: string, entries: [string, string[]][]): void {
    this.queue.push([stream, entries]);
  }

  async xgroup(command: string, stream: string, group: string): Promise<void> {
    if (this.down) {
      throw new Error("connect ECONNREFUSED 10.96.88.38:6379");
    }
    if (command !== "CREATE") {
      return;
    }
    const key = `${stream}/${group}`;
    if (this.groups.has(key)) {
      throw new Error("BUSYGROUP Consumer Group name already exists");
    }
    this.groups.add(key);
    this.createdGroups.push(key);
  }

  async call(command: string, ...args: unknown[]): Promise<unknown> {
    if (command === "XINFO") {
      return [];
    }
    assert.equal(command, "XREADGROUP");
    // Model the real XREADGROUP BLOCK: yield to the macrotask queue so the
    // poll loop cannot starve timers by spinning purely on microtasks.
    await new Promise((resolve) => setTimeout(resolve, 5));
    if (this.down) {
      throw new Error("connect ECONNREFUSED 10.96.88.38:6379");
    }
    const group = String(args[1]);
    const streams = this.queue[0]?.[0];
    if (streams && !this.groups.has(`${streams}/${group}`)) {
      throw new Error(NOGROUP_ERROR);
    }
    const next = this.queue.shift();
    return next ? [next] : null;
  }

  async xack(_stream: string, _group: string, entryId: string): Promise<number> {
    this.acked.push(entryId);
    return 1;
  }

  async xadd(): Promise<string> {
    return "1-1";
  }

  async xautoclaim(): Promise<unknown[]> {
    return ["0-0", []];
  }

  async xpending(): Promise<unknown[]> {
    return [];
  }
}

async function waitFor(predicate: () => boolean, timeoutMs = 5_000, label = "condition"): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  assert.fail(`${label} was not met within ${timeoutMs}ms`);
}

function silenceConsole(): () => void {
  const { warn, info, error } = console;
  console.warn = () => undefined;
  console.info = () => undefined;
  console.error = () => undefined;
  return () => {
    console.warn = warn;
    console.info = info;
    console.error = error;
  };
}

describe("Redis client resilience options", () => {
  it("retries reconnects indefinitely with capped exponential backoff", () => {
    // Every attempt must yield a NUMBER: ioredis' closeHandler stops
    // reconnecting entirely as soon as retryStrategy returns a non-number.
    for (const attempt of [1, 2, 5, 20, 500, 100_000]) {
      const delay = redisRetryStrategy(attempt);
      assert.equal(typeof delay, "number", `attempt ${attempt} must keep retrying`);
      assert.ok(delay > 0 && delay <= 30_000, `attempt ${attempt} delay ${delay} must be capped`);
    }

    // Backoff must actually grow, then plateau at the cap.
    assert.ok(redisRetryStrategy(1) < redisRetryStrategy(4));
    assert.ok(redisRetryStrategy(4) < redisRetryStrategy(8));
    assert.equal(redisRetryStrategy(50), redisRetryStrategy(5_000));
  });

  it("never flushes the command queue with MaxRetriesPerRequestError", () => {
    const options = redisClientOptions();
    // ioredis defaults this to 20: after 20 reconnect attempts it rejects
    // every queued command, which is what killed the blocking XREADGROUP.
    assert.equal(options.maxRetriesPerRequest, null);
    assert.equal(options.enableOfflineQueue, true);
assert.equal(typeof options.retryStrategy, "function");
  });

  it("lets callers override defaults", () => {
    assert.equal(redisClientOptions({ maxRetriesPerRequest: 3 }).maxRetriesPerRequest, 3);
  });

  it("attaches an error listener so ioredis never falls back to 'Unhandled error event'", () => {
    // `new Redis(url, { lazyConnect: true })` -- the pre-fix construction --
    // has NO error listener, so ioredis' silentEmit() degrades to
    //   console.error("[ioredis] Unhandled error event:", error.stack)
    // which is literally the last line the account pod logged on 2026-09-06
    // before 17 hours of silence.
    const client = createRedisClient("redis://127.0.0.1:6379", { lazyConnect: true }, "test-client");
    try {
      const emitter = client as unknown as { listenerCount: (event: string) => number };
      assert.ok(emitter.listenerCount("error") > 0, "an error listener must be attached");
      assert.ok(emitter.listenerCount("ready") > 0, "a ready listener must track reconnects");
      assert.ok(emitter.listenerCount("close") > 0, "a close listener must track drops");

      const options = (client as unknown as { options: Record<string, unknown> }).options;
      assert.equal(options.maxRetriesPerRequest, null);
      assert.equal(options.retryStrategy, redisRetryStrategy);
    } finally {
      client.disconnect();
    }
  });

  it("registers a liveness component that starts unhealthy until the client is ready", () => {
    const before = livenessState().components.length;
    const client = createRedisClient("redis://127.0.0.1:6379", { lazyConnect: true }, "probe-client");
    try {
      const state = livenessState();
      assert.equal(state.components.length, before + 1);
      const component = state.components.find((candidate) => candidate.name === "probe-client.connection");
      assert.ok(component, "the client must register a liveness component");
      assert.equal(component.healthy, false, "a never-connected client is not healthy");
      // ...but it must NOT fail liveness immediately: only past the grace period.
      assert.equal(component.expired, false);
      assert.equal(component.gracePeriodMs, DEFAULT_LIVENESS_GRACE_MS);
    } finally {
      client.disconnect();
    }
  });
});

describe("RedisEventSubscriber reconnect behaviour", () => {
  it("resumes consuming after the Redis connection drops and returns", async () => {
    const redis = new RestartableFakeRedis();
    const subscriber = new RedisEventSubscriber(redis as never, () => {});
    const handled: string[] = [];
const restore = silenceConsole();

    try {
      const first = envelope("AccountCreated");
      redis.enqueue("events:account", [entryFor("1-0", first)]);

      await subscriber.subscribe(["events:account"], "account", "account-1", (received) => {
    handled.push(received.eventId);
  return { ok: true };
   });

      await waitFor(() => handled.length === 1, 5_000, "first event consumed");

      // Redis dies mid-flight, exactly as on 2026-09-06.
redis.goDown();
    await new Promise((resolve) => setTimeout(resolve, 150));

      // ...and comes back healthy 10s later (empty, as a restart implies).
      redis.comeBackEmpty();
      const second = envelope("AccountFrozen");
    redis.enqueue("events:account", [entryFor("2-0", second)]);

      // The consumer must RESUME. Before the fix the loop was dead and this
  // event was never delivered.
      await waitFor(() => handled.length === 2, 5_000, "event after reconnect consumed");
      assert.deepEqual(handled, [first.eventId, second.eventId]);
      assert.ok(redis.acked.includes("2-0"), "post-reconnect event must be acked");
    } finally {
      await subscriber.stop();
      restore();
    }
  });

  it("re-creates the consumer group after a Redis wipe instead of erroring on NOGROUP forever", async () => {
    const redis = new RestartableFakeRedis();
    const subscriber = new RedisEventSubscriber(redis as never, () => {});
    const handled: string[] = [];
    const restore = silenceConsole();

    try {
      await subscriber.subscribe(["events:account"], "account", "account-1", (received) => {
  handled.push(received.eventId);
        return { ok: true };
      });
      await waitFor(() => redis.createdGroups.length === 1, 2_000, "initial group created");

      // Wipe: the restarted Redis has no stream and no consumer group.
      redis.goDown();
      await new Promise((resolve) => setTimeout(resolve, 100));
      redis.comeBackEmpty();

      const replayed = envelope("AccountCreated");
      redis.enqueue("events:account", [entryFor("5-0", replayed)]);

      await waitFor(() => redis.createdGroups.length >= 2, 5_000, "group re-created after wipe");
      await waitFor(() => handled.includes(replayed.eventId), 5_000, "event consumed after group re-create");
      assert.deepEqual(redis.createdGroups.slice(0, 2), ["events:account/account", "events:account/account"]);
    } finally {
      await subscriber.stop();
      restore();
    }
  });

  it("classifies NOGROUP errors so the group is re-created", () => {
    assert.equal(isMissingGroupError(new Error(NOGROUP_ERROR)), true);
  assert.equal(isMissingGroupError(NOGROUP_ERROR), true);
    assert.equal(isMissingGroupError(new Error("connect ECONNREFUSED 10.96.88.38:6379")), false);
  });

  it("restarts a consumer loop whose promise rejects instead of dying silently", async () => {
    // A read path that throws a non-Error, non-recoverable rejection used to
    // escape the loop; startLoop() only logged it and consumption stopped for
    // the lifetime of the process.
    let reads = 0;
    const failures: unknown[] = [];
    const message = envelope("AccountCreated");
    const redis = {
      status: "ready",
      async connect(): Promise<void> {},
      disconnect(): void {},
      async xgroup(): Promise<void> {},
      async xautoclaim(): Promise<unknown[]> { return ["0-0", []]; },
      async xpending(): Promise<unknown[]> { return []; },
      async xadd(): Promise<string> { return "1-1"; },
      acked: [] as string[],
      async xack(_s: string, _g: string, id: string): Promise<number> { this.acked.push(id); return 1; },
      async call(command: string): Promise<unknown> {
        if (command === "XINFO") return [];
        reads += 1;
        // Yield to timers so the loop cannot starve the event loop.
        await new Promise((resolve) => setTimeout(resolve, 5));
        if (reads === 1) {
          // Rejection that is NOT in the recoverable list.
          throw new Error("unexpected driver explosion");
        }
        if (reads === 2) {
          return [["events:account", [entryFor("9-0", message)]]];
        }
        return null;
      },
    };

    const subscriber = new RedisEventSubscriber(redis as never, (error) => failures.push(error));
    const handled: string[] = [];
  const restore = silenceConsole();
    try {
 await subscriber.subscribe(["events:account"], "account", "account-1", (received) => {
   handled.push(received.eventId);
        return { ok: true };
      });

      // Consumption must continue past the unexpected failure.
      await waitFor(() => handled.includes(message.eventId), 5_000, "consumption survived unexpected error");
      assert.ok(failures.length >= 1, "unexpected errors must still be reported");
    } finally {
 await subscriber.stop();
      restore();
    }
  });
  it("restarts a supervised loop whose promise rejects, instead of only logging it", async () => {
    // The pre-fix startLoop() accepted an already-started promise and did
    //   loop.catch((error) => this.onLoopFailure(...))
    // so a single rejection ended consumption for the life of the process.
    // The supervisor takes a FACTORY and re-invokes it.
    const subscriber = new RedisEventSubscriber({ disconnect() {} } as never, () => {});
    const restore = silenceConsole();
    let invocations = 0;
    try {
      const supervise = (subscriber as unknown as {
        superviseLoop: (name: string, group: string, factory: () => Promise<void>, signal?: AbortSignal) => void;
      }).superviseLoop.bind(subscriber);

      supervise("poll", "account", async () => {
        invocations += 1;
        if (invocations < 3) {
          throw new Error("loop exploded");
        }
      });

      await waitFor(() => invocations >= 3, 5_000, "crashed loop restarted");
      assert.ok(invocations >= 3, "loop must be restarted after each rejection");
    } finally {
      await subscriber.stop();
      restore();
    }
  });

  it("treats a MaxRetriesPerRequestError as a recoverable reconnect, not a loop failure", async () => {
    // ioredis' default maxRetriesPerRequest:20 rejects in-flight commands
    // (including the blocking XREADGROUP) after 20 reconnect attempts. That
    // rejection must be handled as "Redis is bouncing", not escalated.
    const message = envelope("AccountCreated");
    let reads = 0;
    const escalated: unknown[] = [];
    const redis = {
      status: "ready",
      disconnect(): void {},
      async xgroup(): Promise<void> {},
      async xautoclaim(): Promise<unknown[]> { return ["0-0", []]; },
      async xpending(): Promise<unknown[]> { return []; },
      async xadd(): Promise<string> { return "1-1"; },
      async xack(): Promise<number> { return 1; },
      async call(command: string): Promise<unknown> {
        if (command === "XINFO") return [];
        reads += 1;
        await new Promise((resolve) => setTimeout(resolve, 5));
        if (reads === 1) {
          throw new Error("Reached the max retries per request limit (which is 20). Refer to \"maxRetriesPerRequest\" option for details.");
        }
        if (reads === 2) {
          return [["events:account", [entryFor("11-0", message)]]];
        }
        return null;
      },
    };

    const subscriber = new RedisEventSubscriber(redis as never, (error) => escalated.push(error));
    const handled: string[] = [];
    const restore = silenceConsole();
    try {
      await subscriber.subscribe(["events:account"], "account", "account-1", (received) => {
        handled.push(received.eventId);
        return { ok: true };
      });
      await waitFor(() => handled.includes(message.eventId), 5_000, "consumption resumed after retry-limit error");
      assert.deepEqual(escalated, [], "a reconnect-driven rejection must not be reported as a loop failure");
    } finally {
      await subscriber.stop();
      restore();
    }
  });
});

describe("OutboxRelay supervision", () => {
  it("keeps relaying after a failure reporter throws, instead of dying silently", async () => {
    // Pre-fix: `this.loop = this.run().catch(onFailure)`. A reporter that
    // throws propagated out of run(), the rejection was swallowed by the
    // outer .catch, and the relay never published another event for the life
    // of the process -- while /healthz still returned 200.
    let queries = 0;
    const pool = {
      async query(): Promise<never> {
        queries += 1;
        throw new Error("PostgreSQL unavailable");
      },
    };
    const relay = new OutboxRelay(pool as never, {} as never, {
      pollIntervalMs: 5,
      trackLiveness: false,
      onFailure: () => {
        throw new Error("logger exploded");
      },
    });

    const restore = silenceConsole();
    try {
      relay.start();
      await waitFor(() => queries >= 5, 5_000, "relay kept polling despite a throwing reporter");
      assert.ok(queries >= 5, `relay must keep polling; saw ${queries} attempts`);
    } finally {
      await relay.stop();
      restore();
    }
  });

  it("marks itself unhealthy while failing and healthy again once publishing resumes", async () => {
    let failing = true;
    const pool = {
      async query(sql: string): Promise<unknown> {
        if (failing) {
          throw new Error("PostgreSQL unavailable");
        }
        return String(sql).includes("SELECT") ? { rows: [] } : { rows: [] };
      },
    };
    const relay = new OutboxRelay(pool as never, {} as never, { pollIntervalMs: 5, name: "test-relay" });
    const restore = silenceConsole();
    try {
      relay.start();
      const component = () => livenessState().components.find((candidate) => candidate.name === "test-relay");
      await waitFor(() => component()?.healthy === false, 3_000, "relay reports unhealthy while failing");
      // A failing relay must not fail liveness immediately.
      assert.equal(component()?.expired, false);

      failing = false;
      await waitFor(() => component()?.healthy === true, 3_000, "relay recovers");
    } finally {
      await relay.stop();
      restore();
    }
    assert.equal(livenessState().components.find((candidate) => candidate.name === "test-relay"), undefined);
  });
});

describe("liveness grace period", () => {
it("defaults to a grace period materially longer than a normal reconnect", () => {
    // Worst case notice-the-reconnect delay is the 30s retry cap; the grace
    // period must leave a wide margin over that.
    assert.equal(DEFAULT_LIVENESS_GRACE_MS, 5 * 60 * 1_000);
    assert.ok(DEFAULT_LIVENESS_GRACE_MS >= 30_000 * 5, "grace must dwarf the reconnect cap");
    assert.equal(livenessGraceMs(), DEFAULT_LIVENESS_GRACE_MS);
  });

  it("stays live through a transient blip and fails only after the grace period", () => {
    let now = 1_000_000;
    const registry = new LivenessRegistry();
    const consumer = registry.register("redis-consumer.account", { gracePeriodMs: 60_000, clock: () => now });

    consumer.markHealthy();
    assert.equal(registry.state().live, true);

    // A blip: unhealthy for 5s, then reconnected. Liveness must NOT fail --
  // flapping the pod on every Redis hiccup would be its own outage.
    consumer.markUnhealthy("connect ECONNREFUSED");
    now += 5_000;
    assert.equal(registry.state().live, true, "5s outage must not fail liveness");
    consumer.markHealthy();
    now += 10_000;
    assert.equal(registry.state().live, true);

    // A wedge: continuously unhealthy past the grace period.
    consumer.markUnhealthy("poll failing: Connection is closed");
    now += 59_000;
  assert.equal(registry.state().live, true, "still inside the grace period");
    now += 2_000;

    const state = registry.state();
    assert.equal(state.live, false, "liveness must fail once the consumer is dead past grace");
    assert.equal(state.failed.length, 1);
    assert.equal(state.failed[0]?.name, "redis-consumer.account");
    assert.match(String(state.failed[0]?.reason), /Connection is closed/);
  });

  it("resets the unhealthy clock on recovery so repeated blips never accumulate", () => {
    let now = 5_000_000;
    const registry = new LivenessRegistry();
    const consumer = registry.register("redis-consumer.account", { gracePeriodMs: 60_000, clock: () => now });
    consumer.markHealthy();

    for (let cycle = 0; cycle < 10; cycle += 1) {
      consumer.markUnhealthy("transient drop");
      now += 50_000;
      assert.equal(registry.state().live, true, `blip ${cycle} must not fail liveness`);
consumer.markHealthy();
      now += 1_000;
    }
    assert.equal(registry.state().live, true);
  });

  it("ignores components that were disposed on shutdown", () => {
    let now = 0;
    const registry = new LivenessRegistry();
    const consumer = registry.register("redis-consumer.account", { gracePeriodMs: 1_000, clock: () => now });
    consumer.markHealthy();
    consumer.markUnhealthy("stopping");
    now += 5_000;
    assert.equal(registry.state().live, false);

    consumer.dispose();
    assert.equal(registry.state().live, true, "disposed components must not fail liveness");
    assert.equal(registry.state().components.length, 0);
  });

  it("never fails liveness for a component that has never been healthy", () => {
    // A client nobody connected, or a dependency that is slow at boot, must
    // not restart the pod: that is what readiness and initialDelaySeconds
    // are for. Liveness only fires for something that worked and then stuck.
    let now = 0;
    const registry = new LivenessRegistry();
    const neverUp = registry.register("redis-consumer.unused", { gracePeriodMs: 1_000, clock: () => now });
    neverUp.markUnhealthy("not yet connected");
    now += 10 * 60 * 1_000;

    assert.equal(registry.state().live, true);
    assert.equal(registry.state().components[0]?.expired, false);

    // Once it has worked, the same outage does count.
    neverUp.markHealthy();
    neverUp.markUnhealthy("Connection is closed");
    now += 5_000;
    assert.equal(registry.state().live, false);
  });

  it("is live when nothing has registered yet", () => {
    assert.equal(new LivenessRegistry().state().live, true);
  });
});
