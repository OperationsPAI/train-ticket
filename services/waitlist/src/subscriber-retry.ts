import {
  connectRedisWithRetry,
  createRedisClient,
  redisClientLiveness,
  redisRetryStrategy,
  RedisStreamEventSubscriber,
  type EventHandler,
} from "@trainticket/ts-kit";

/**
 * Background subscribe retry for waitlist.
 *
 * Shape of the gap this closes (different from loyalty-membership's):
 * `bootstrap` connected Redis via `createRedisMessagingAdapters` -- which uses
 * `connectRedisWithRetry`, so the CLIENT is healthy and
 * `subscriber.connection` reports healthy -- and then called `subscribe()` and
 * merely logged the rejection while aborting the AbortController. The result
 * was a live, healthy Redis client with dead consumption and NO
 * `redis-consumer.waitlist` component in the liveness registry, so nothing
 * could ever fail `/healthz`. `ancillary-service` and `offer-management`
 * rethrow into an uncaught exception on this path; that surfaces the fault but
 * cannot repair it, and it discards waitlist's still-working HTTP API.
 *
 * This module retries indefinitely with `redisRetryStrategy`'s capped
 * exponential backoff (100ms -> 30s), the same schedule every other Redis
 * reconnect in the codebase uses. The never-healthy liveness guard is left
 * untouched: it is what prevents a boot-time Redis outage from restart-storming
 * all seven TypeScript services simultaneously. Recovery comes from the retry,
 * not from a restart.
 */

export type EventConsumptionState = "consuming" | "retrying";

export type WaitlistSubscribeAttempt = Readonly<{ close: () => Promise<void> }>;

export type SuperviseWaitlistSubscribeOptions = Readonly<{
  subscribe: () => Promise<WaitlistSubscribeAttempt>;
  backoffMs?: (attempt: number) => number;
  sleep?: (milliseconds: number) => Promise<void>;
  log?: (entry: Record<string, unknown>) => void;
}>;

export type WaitlistSubscribeSupervisor = Readonly<{
  state: () => EventConsumptionState;
  attempts: () => number;
  /** Resolves once the first attempt has settled, successfully or not. */
  settled: () => Promise<void>;
  /** Resolves once an attempt has succeeded and consumption is live. */
  consuming: () => Promise<void>;
  close: () => Promise<void>;
}>;

export function superviseWaitlistSubscribe(options: SuperviseWaitlistSubscribeOptions): WaitlistSubscribeSupervisor {
  const backoffMs = options.backoffMs ?? redisRetryStrategy;
  const sleep = options.sleep ?? defaultSleep;
  const log = options.log ?? ((entry: Record<string, unknown>) => console.warn(entry));

  let state: EventConsumptionState = "retrying";
  let attempts = 0;
  let stopped = false;
  let attached: WaitlistSubscribeAttempt | undefined;
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
        attached = subscribed;
        state = "consuming";
        if (attempt > 1) {
          log({ service: "waitlist", dependency: "redis", attempt, message: "event subscriber recovered; stream consumption resumed without a restart" });
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
          service: "waitlist",
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
      await attached?.close().catch(() => undefined);
    },
  };
}

/**
 * One subscribe attempt, on a FRESH subscriber and a fresh Redis client.
 *
 * Both must be fresh per attempt. `RedisEventSubscriber.subscribe()` sets
 * `stopped = true` and permanently rejects its `started()` promise when it
 * fails, so a retried `subscribe()` on the same instance would register a
 * `redis-consumer.waitlist` component, mark it HEALTHY, and then have both
 * supervised loops exit immediately on the `stopped` check -- producing a green
 * liveness probe over a consumer that reads nothing. That is precisely the
 * 2026-09-06 failure mode, so reuse is not an option here.
 */
export async function subscribeWaitlistOnce(
  redisUrl: string,
  streams: readonly string[],
  group: string,
  consumer: string,
  handler: EventHandler,
  signal?: AbortSignal,
): Promise<WaitlistSubscribeAttempt> {
  const redis = createRedisClient(redisUrl, {}, "waitlist-subscriber");
  const subscriber = new RedisStreamEventSubscriber(redis, undefined, { thrownHandlerErrors: "dlq" });
  const release = async (): Promise<void> => {
    await subscriber.stop().catch(() => undefined);
    await subscriber.close().catch(() => undefined);
    // Drop this attempt's connection liveness component so failed attempts do
    // not accumulate never-healthy components in the registry.
    redisClientLiveness(redis)?.dispose();
    await redis.quit().catch(() => undefined);
  };
  try {
    await connectRedisWithRetryOnce(redis);
    await subscriber.subscribe(streams, group, consumer, handler, signal);
    return { close: release };
  } catch (error) {
    await release();
    throw error;
  }
}

/**
 * `connectRedisWithRetry` never returns until it connects, which would hide a
 * down Redis inside the connect step instead of surfacing it as a failed
 * attempt the supervisor can log and back off from. A single attempt keeps the
 * retry accounting in one place; the client itself still reconnects on its own
 * once established.
 */
async function connectRedisWithRetryOnce(redis: Parameters<typeof connectRedisWithRetry>[0]): Promise<void> {
  const status = (redis as unknown as { status?: string }).status;
  if (status === "ready" || status === "connect" || status === "connecting") {
    return;
  }
  await redis.connect();
}

function defaultSleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, milliseconds);
    timer.unref?.();
  });
}
