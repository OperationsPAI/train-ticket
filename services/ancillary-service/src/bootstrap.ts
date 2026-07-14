import { createRedisMessagingAdapters } from "./adapters/messaging/redis.js";
import { CONSUMER_GROUP, SUBSCRIBED_STREAMS, consumerName } from "./adapters/messaging/stream-config.js";
import { startAncillaryStorage } from "./adapters/storage/runtime.js";
import { AncillaryApplicationService, InMemoryAncillaryRepository } from "./application.js";
import { createApp, opentelemetryInstrumentationFromEnv, type InstrumentationHooks } from "./app.js";
import { HttpFarePricingGateway } from "./pricing.js";

export type BootstrapOptions = Readonly<{ host?: string; port?: number; instrumentation?: InstrumentationHooks; redisUrl?: string; instanceId?: string }>;
export function runtimeHost(options: Pick<BootstrapOptions, "host"> = {}): string { return options.host ?? process.env.HOST ?? "0.0.0.0"; }
export function runtimePort(options: Pick<BootstrapOptions, "port"> = {}): number { if (options.port !== undefined) return options.port; const configured = Number.parseInt(process.env.PORT ?? "", 10); return Number.isFinite(configured) ? configured : 8080; }
export function runtimeRedisUrl(options: Pick<BootstrapOptions, "redisUrl"> = {}): string { return options.redisUrl ?? process.env.REDIS_URL ?? "redis://localhost:6379"; }
function farePricingGatewayFromEnv(): HttpFarePricingGateway | undefined { const baseUrl = process.env.FARE_PRICING_URL?.trim(); return baseUrl ? new HttpFarePricingGateway(baseUrl) : undefined; }
export async function bootstrap(options: BootstrapOptions = {}) {
  const messaging = await createRedisMessagingAdapters(runtimeRedisUrl(options));
  const storage = process.env.DATABASE_URL ? await startAncillaryStorage(runtimeRedisUrl(options)) : undefined;
  const repository = new InMemoryAncillaryRepository();
  const pricingGateway = farePricingGatewayFromEnv();
  const application = new AncillaryApplicationService(repository, messaging.publisher, pricingGateway);
  const app = createApp(options.instrumentation ?? opentelemetryInstrumentationFromEnv(), { repository, publisher: messaging.publisher, pricingGateway, idempotencyStore: storage?.idempotencyStore, storage });
  const abortController = new AbortController();
  const expiryTimer = setInterval(() => {
    void (storage ? storage.runCommand((svc) => svc.expireOffers()) : application.expireOffers()).catch((error: unknown) => app.log.warn({ err: error }, "ancillary offer expiry scan failed"));
  }, Number.parseInt(process.env.OFFER_EXPIRY_SCAN_MS ?? "1000", 10));
  expiryTimer.unref?.();
  const subscription = messaging.subscriber.subscribe(SUBSCRIBED_STREAMS, CONSUMER_GROUP, consumerName(options.instanceId ?? process.env.HOSTNAME), async (envelope) => storage ? storage.handleUpstreamEvent(envelope) : application.handleJourneyOrderCancelled(envelope), abortController.signal);
  const subscriptionFailed = new Promise<never>((_, reject) => { subscription.catch((error: unknown) => { app.log.error({ err: error }, "ancillary-service event subscriber stopped"); abortController.abort(); reject(error); setImmediate(() => { throw error; }); }); });
  await Promise.race([messaging.subscriber.started(), subscriptionFailed]);
  app.addHook("onClose", async () => { clearInterval(expiryTimer); abortController.abort(); messaging.subscriber.stop?.(); await messaging.close(); await storage?.stop(); });
  await app.listen({ host: runtimeHost(options), port: runtimePort(options) });
  return app;
}
