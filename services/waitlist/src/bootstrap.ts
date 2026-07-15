import { createRedisMessagingAdapters, streamForProducer, type EventEnvelope } from "@trainticket/ts-kit";
import { createApp } from "./app.js";
import { WaitlistApplicationService } from "./application.js";
import { createWaitlistEventHandler, SUBSCRIBED_STREAMS, CONSUMER_GROUP, consumerName } from "./subscriber.js";
import { startWaitlistStorage } from "./storage.js";
import type { WaitlistCapacityFreed } from "./promotion.js";

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
  const app = createApp({ publisher: messaging.publisher, idempotencyStore: storage?.idempotencyStore, storage, applicationService });
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
  const subscription = messaging.subscriber.subscribe(SUBSCRIBED_STREAMS, CONSUMER_GROUP, consumerName(options.instanceId ?? process.env.HOSTNAME), createWaitlistEventHandler(eventService), abortController.signal);
  subscription.catch((error: unknown) => {
    app.log.error({ err: error }, "waitlist event subscriber stopped");
    abortController.abort();
  });
  const expiryTimer = setInterval(() => {
    const operation = storage
      ? storage.runCommand((repository, publisher) => new WaitlistApplicationService(repository, publisher).expireDueOffers())
      : inMemoryApplicationService().expireDueOffers();
    operation.catch((error: unknown) => app.log.error({ err: error }, "waitlist offer expiry scan failed"));
  }, Number.parseInt(process.env.WAITLIST_EXPIRY_SCAN_MS ?? "60000", 10));
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
    messaging.subscriber.stop?.();
    await messaging.close();
    await storage?.stop();
  });
  await app.listen({ host: runtimeHost(options), port: runtimePort(options) });
  return app;
}
