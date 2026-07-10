import { createRedisMessagingAdapters } from "./adapters/messaging/redis.js";
import { CONSUMER_GROUP, SUBSCRIBED_STREAMS, consumerName } from "./adapters/messaging/stream-config.js";
import { createApp, opentelemetryInstrumentationFromEnv, type InstrumentationHooks } from "./app.js";
import { createUpstreamEventHandler, InMemoryUpstreamStateRepository } from "./application/upstream-state.js";
import { startOfferStorage } from "./adapters/storage/runtime.js";

export type BootstrapOptions = Readonly<{
  host?: string;
  port?: number;
  instrumentation?: InstrumentationHooks;
  redisUrl?: string;
  instanceId?: string;
}>;

export function runtimeHost(options: Pick<BootstrapOptions, "host"> = {}): string {
  return options.host ?? process.env.HOST ?? "0.0.0.0";
}

export function runtimePort(options: Pick<BootstrapOptions, "port"> = {}): number {
  if (options.port !== undefined) {
    return options.port;
  }
  const configured = Number.parseInt(process.env.PORT ?? "", 10);
  return Number.isFinite(configured) ? configured : 8080;
}

/**
 * Resolve the Redis connection URL from options or the REDIS_URL environment
 * variable. Falls back to the default local Redis.
 */
export function runtimeRedisUrl(options: Pick<BootstrapOptions, "redisUrl"> = {}): string {
  return options.redisUrl ?? process.env.REDIS_URL ?? "redis://localhost:6379";
}

export async function bootstrap(options: BootstrapOptions = {}) {
  const messaging = await createRedisMessagingAdapters(runtimeRedisUrl(options));
  const storage = process.env.DATABASE_URL ? await startOfferStorage(runtimeRedisUrl(options)) : undefined;
  const upstreamRepository = new InMemoryUpstreamStateRepository();
  const app = createApp(options.instrumentation ?? opentelemetryInstrumentationFromEnv(), {
    publisher: messaging.publisher,
    upstreamRepository,
    idempotencyStore: storage?.idempotencyStore,
    storage,
  });

  const abortController = new AbortController();
  const upstreamEventHandler = storage?.handleUpstreamEvent ?? createUpstreamEventHandler(upstreamRepository);
  const subscription = messaging.subscriber.subscribe(
    SUBSCRIBED_STREAMS,
    CONSUMER_GROUP,
    consumerName(options.instanceId ?? process.env.HOSTNAME),
    upstreamEventHandler,
    abortController.signal,
  );
  const subscriptionFailed = new Promise<never>((_, reject) => {
    subscription.catch((error: unknown) => {
      app.log.error({ err: error }, "offer-management event subscriber stopped");
      abortController.abort();
      reject(error);
      setImmediate(() => {
        throw error;
      });
    });
  });

  await Promise.race([
    messaging.subscriber.started(),
    subscriptionFailed,
  ]);

  app.addHook("onClose", async () => {
    abortController.abort();
    messaging.subscriber.stop();
    await messaging.close();
    await storage?.stop();
  });

  await app.listen({ host: runtimeHost(options), port: runtimePort(options) });
  return app;
}
