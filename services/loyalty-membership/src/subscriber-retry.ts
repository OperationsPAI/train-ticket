import { redisRetryStrategy } from "@trainticket/ts-kit";

/**
 * Background subscribe retry.
 *
 * Background: on 2026-09-06 the `account` pod stayed up and healthy-looking for
 * 20 hours with a dead stream consumer. `platform/ts-kit` fixed the *post*-
 * subscribe half of that (resilient clients, supervised poll/recover loops, a
 * `redis-consumer.*` liveness component). The half it cannot fix is subscribe
 * time: if `subscribe()` itself rejects, no consumer component is ever
 * registered, and `LivenessComponent`'s deliberate never-healthy guard means
 * `/healthz` correctly refuses to fail. `startSubscriber` used to swallow that
 * rejection and serve HTTP forever with zero event consumption behind a green
 * `/healthz`. A restart would not have helped: the failure is at subscribe
 * time and would simply recur.
 *
 * The fix is to make a subscribe failure transient rather than terminal, using
 * the same idiom `connectRedisWithRetry` already uses for connects: retry
 * indefinitely with `redisRetryStrategy`'s capped exponential backoff
 * (100ms -> 30s). We deliberately do NOT weaken the never-healthy guard --
 * that guard is what stops a boot-time Redis outage from restart-storming all
 * seven TypeScript services at once. The retry is the fix; the guard stays.
 *
 * Once a subscribe attempt succeeds the supervisor is done and hands over: the
 * ts-kit consumer component is registered and healthy, so a LATER wedge is
 * caught by `livenessProbe()` on the existing path.
 */

export type EventConsumptionState = "consuming" | "retrying";

/** The lifecycle surface the supervisor needs from a subscribed consumer. */
export type SupervisedConsumer = Readonly<{ close: () => Promise<void> }>;

export type SuperviseSubscribeOptions<T extends SupervisedConsumer> = Readonly<{
  /**
   * Create a FRESH consumer and subscribe it, rejecting if subscription failed.
   *
   * It must be fresh on every call: a `RedisEventSubscriber` whose `subscribe()`
   * rejected sets `stopped = true` permanently, so re-subscribing the same
   * instance registers a healthy liveness component whose supervised loops exit
   * immediately -- a green probe over silent non-consumption, i.e. exactly the
   * outage this code exists to prevent.
   */
  subscribe: () => Promise<T>;
  /** Capped exponential backoff. Defaults to the shared Redis reconnect schedule. */
  backoffMs?: (attempt: number) => number;
  sleep?: (milliseconds: number) => Promise<void>;
  log?: (entry: Record<string, unknown>) => void;
}>;

export type SubscribeSupervisor = Readonly<{
  /** "retrying" until a subscribe attempt has succeeded, "consuming" after. */
  state: () => EventConsumptionState;
  /** Number of subscribe attempts made so far. */
  attempts: () => number;
  /** Resolves once the first attempt has settled, successfully or not. */
  settled: () => Promise<void>;
  /** Resolves once an attempt has succeeded and consumption is live. */
  consuming: () => Promise<void>;
  close: () => Promise<void>;
}>;

export function superviseSubscribe<T extends SupervisedConsumer>(options: SuperviseSubscribeOptions<T>): SubscribeSupervisor {
  const backoffMs = options.backoffMs ?? redisRetryStrategy;
  const sleep = options.sleep ?? defaultSleep;
  const log = options.log ?? ((entry: Record<string, unknown>) => console.warn(entry));

  let state: EventConsumptionState = "retrying";
  let attempts = 0;
  let stopped = false;
  let consumer: T | undefined;
  // Resolved by close() so a shutdown does not sit through a backoff that may
  // be up to 30s long.
  let interrupt!: () => void;
  const interrupted = new Promise<void>((resolve) => {
    interrupt = resolve;
  });

  let settleFirst!: () => void;
  const firstSettled = new Promise<void>((resolve) => {
    settleFirst = resolve;
  });
  let markConsuming!: () => void;
  const consumingPromise = new Promise<void>((resolve) => {
    markConsuming = resolve;
  });

  const loop = (async () => {
    for (let attempt = 1; !stopped; attempt += 1) {
      attempts = attempt;
      try {
        const subscribed = await options.subscribe();
        if (stopped) {
          await subscribed.close().catch(() => undefined);
          return;
        }
        consumer = subscribed;
        state = "consuming";
        if (attempt > 1) {
          log({
            service: "loyalty-membership",
            dependency: "redis",
            attempt,
            message: "event subscriber recovered; stream consumption resumed without a restart",
          });
        }
        settleFirst();
        markConsuming();
        return;
      } catch (error) {
        settleFirst();
        if (stopped) {
          return;
        }
        const retryInMs = backoffMs(attempt);
        log({
          service: "loyalty-membership",
          dependency: "redis",
          attempt,
          retryInMs,
          errorName: error instanceof Error ? error.name : "UnknownError",
          message: "event subscriber unavailable; HTTP API serving without stream consumption, retrying in background",
        });
        await Promise.race([sleep(retryInMs), interrupted]);
      }
    }
  })();

  return {
    state: () => state,
    attempts: () => attempts,
    settled: () => firstSettled,
    consuming: () => consumingPromise,
    close: async () => {
      stopped = true;
      interrupt();
      await loop.catch(() => undefined);
      await consumer?.close().catch(() => undefined);
    },
  };
}

function defaultSleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, milliseconds);
    timer.unref?.();
  });
}
