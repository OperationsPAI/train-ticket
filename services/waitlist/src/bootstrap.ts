import { createRedisMessagingAdapters, streamForProducer, type EventEnvelope } from "@trainticket/ts-kit";
import { createApp } from "./app.js";
import { WaitlistApplicationService } from "./application.js";
import { createWaitlistEventHandler, SUBSCRIBED_STREAMS, CONSUMER_GROUP, consumerName } from "./subscriber.js";
import { startWaitlistStorage } from "./storage.js";
import type { WaitlistCapacityFreed } from "./promotion.js";
import { superviseWaitlistSubscribe, subscribeWaitlistOnce, type WaitlistSubscribeSupervisor } from "./subscriber-retry.js";

export type BootstrapOptions = Readonly<{ host?: string; port?: number; redisUrl?: string; instanceId?: string }>;

export function runtimeHost(options: Pick<BootstrapOptions, "host"> = {}): string { return options.host ?? process.env.HOST ?? "0.0.0.0"; }
export function runtimePort(options: Pick<BootstrapOptions, "port"> = {}): number { return options.port ?? Number.parseInt(process.env.PORT ?? "8080", 10); }
export function runtimeRedisUrl(options: Pick<BootstrapOptions, "redisUrl"> = {}): string { return options.redisUrl ?? process.env.REDIS_URL ?? "redis://localhost:6379"; }

function consumedStream(envelope: EventEnvelope): string {
  return streamForProducer(envelope.producer);
}

export async function bootstrap(options: BootstrapOptions = {}) {
  const messaging = await createRedisMessagingAdapters(runtimeRedisUrl(options));
  const storage = process.env.DATABASE_URL ? await startWaitlistStorage(runtimeRedisUrl(options)) : undefined;
  const applicationService = storage ? undefined : new WaitlistApplicationService(undefined, messaging.publisher);
  // Assigned just below, once `app.log` exists for the supervisor's logger.
  // Until then "retrying" is the truthful answer: nothing is consuming yet.
  let consumption: WaitlistSubscribeSupervisor | undefined;
  const app = createApp({
    publisher: messaging.publisher,
    idempotencyStore: storage?.idempotencyStore,
    storage,
    applicationService,
    eventConsumption: () => consumption?.state() ?? "retrying",
  });
  const abortController = new AbortController();
  const inMemoryApplicationService = (): WaitlistApplicationService => {
    if (!applicationService) throw new Error("In-memory waitlist application service is not available");
    return applicationService;
  };
  const eventService = {
    handleCapacityFreed: (event: WaitlistCapacityFreed, correlationId: string | undefined, envelope: EventEnvelope) => storage
      ? storage.runConsumedEvent(envelope.eventId, consumedStream(envelope), (repository, publisher) => new WaitlistApplicationService(repository, publisher).handleCapacityFreed(event, correlationId))
      : inMemoryApplicationService().handleCapacityFreed(event, correlationId),
    handleJourneyOrderConfirmed: (orderId: string, correlationId: string | undefined, envelope: EventEnvelope) => storage
      ? storage.runConsumedEvent(envelope.eventId, consumedStream(envelope), (repository, publisher) => new WaitlistApplicationService(repository, publisher).handleJourneyOrderConfirmed(orderId, correlationId))
      : inMemoryApplicationService().handleJourneyOrderConfirmed(orderId, correlationId),
    handleJourneyOrderCancelled: (orderId: string, _correlationId: string | undefined, envelope: EventEnvelope) => storage
      ? storage.runConsumedEvent(envelope.eventId, consumedStream(envelope), (repository, publisher) => new WaitlistApplicationService(repository, publisher).handleJourneyOrderCancelled(orderId))
      : inMemoryApplicationService().handleJourneyOrderCancelled(orderId),
  };
  // Retry subscribe in the background rather than logging and continuing.
  //
  // The previous code did:
  //
  //   subscription.catch((error) => { app.log.error(...); abortController.abort(); });
  //
  // which turned a rejected subscribe into a permanent, silent loss of event
  // consumption -- and the `abort()` made it unrecoverable by design. Unlike
  // loyalty-membership the Redis client here is already CONNECTED (see
  // `createRedisMessagingAdapters` above, which uses connectRedisWithRetry), so
  // `subscriber.connection` stays healthy and no `redis-consumer.waitlist`
  // component is ever registered: nothing in the liveness registry can fail,
  // exactly as the never-healthy guard intends. `ancillary-service` and
  // `offer-management` rethrow into an uncaught exception instead, which does
  // at least surface the fault, but a crash-loop cannot fix a dependency that
  // is down -- and waitlist owns HTTP endpoints that keep working meanwhile.
  //
  // So: retry indefinitely with capped backoff. Attempt 1 uses the subscriber
  // the adapters already built, so the happy path is unchanged; retries build a
  // fresh subscriber and client, because a RedisEventSubscriber whose
  // subscribe() rejected is permanently `stopped` and reusing it would yield a
  // healthy consumer component over loops that exit immediately. Once an
  // attempt lands, ts-kit registers the consumer component and the existing
  // liveness path catches any LATER wedge.
  const handler = createWaitlistEventHandler(eventService);
  const consumer = consumerName(options.instanceId ?? process.env.HOSTNAME);
  let firstAttempt = true;
  consumption = superviseWaitlistSubscribe({
    subscribe: async () => {
      if (firstAttempt) {
        firstAttempt = false;
        await messaging.subscriber.subscribe(SUBSCRIBED_STREAMS, CONSUMER_GROUP, consumer, handler, abortController.signal);
        return { close: async () => { messaging.subscriber.stop?.(); } };
      }
      return subscribeWaitlistOnce(runtimeRedisUrl(options), SUBSCRIBED_STREAMS, CONSUMER_GROUP, consumer, handler, abortController.signal);
    },
    log: (entry) => app.log.warn(entry, "waitlist event subscriber unavailable; retrying in background"),
  });
  await consumption.settled();
  const expiryTimer = setInterval(() => {
    const operation = storage
      ? storage.runCommand((repository, publisher) => new WaitlistApplicationService(repository, publisher).expireDueOffers())
      : inMemoryApplicationService().expireDueOffers();
    operation.catch((error: unknown) => app.log.error({ err: error }, "waitlist offer expiry scan failed"));
  }, Number.parseInt(process.env.WAITLIST_EXPIRY_SCAN_MS ?? "15000", 10));
  expiryTimer.unref();
  const archivalTimer = setInterval(() => {
    const operation = storage
      ? storage.runCommand((repository, publisher) => new WaitlistApplicationService(repository, publisher).archiveTerminalRequests())
      : inMemoryApplicationService().archiveTerminalRequests();
    operation.catch((error: unknown) => app.log.error({ err: error }, "waitlist archival sweep failed"));
  }, Number.parseInt(process.env.WAITLIST_ARCHIVAL_SWEEP_MS ?? "300000", 10));
  archivalTimer.unref();
  app.addHook("onClose", async () => {
    clearInterval(expiryTimer);
    clearInterval(archivalTimer);
    abortController.abort();
    // Stops the retry loop as well as the attached subscriber, so a shutdown
    // during the retry window does not leave a backoff timer running.
    await consumption?.close();
    await messaging.close();
    await storage?.stop();
  });
  await app.listen({ host: runtimeHost(options), port: runtimePort(options) });
  return app;
}
