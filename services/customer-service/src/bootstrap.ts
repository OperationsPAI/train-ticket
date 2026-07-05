import { randomUUID } from "node:crypto";

import { CustomerServiceApplication } from "./application/customer-service.js";
import { createApp, opentelemetryInstrumentationFromEnv, type InstrumentationHooks } from "./app.js";
import { RedisEventPublisher } from "./adapters/messaging/publisher.js";
import { RedisEventSubscriber } from "./adapters/messaging/subscriber.js";
import { CUSTOMER_SERVICE_CONSUMER_GROUP, CUSTOMER_SERVICE_SUBSCRIPTIONS } from "./adapters/messaging/stream-config.js";

export type BootstrapOptions = Readonly<{
  host?: string;
  port?: number;
  instrumentation?: InstrumentationHooks;
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

export async function bootstrap(options: BootstrapOptions = {}) {
  const publisher = new RedisEventPublisher();
  const application = new CustomerServiceApplication(publisher);
  const subscriber = new RedisEventSubscriber();
  await subscriber.subscribe(
    CUSTOMER_SERVICE_SUBSCRIPTIONS,
    CUSTOMER_SERVICE_CONSUMER_GROUP,
    `${CUSTOMER_SERVICE_CONSUMER_GROUP}-${process.env.HOSTNAME ?? randomUUID()}`,
    (envelope) => application.handleIntegrationEvent(envelope),
  );

  const app = createApp({
    instrumentation: options.instrumentation ?? opentelemetryInstrumentationFromEnv(),
    publisher,
    application,
  });
  app.addHook("onClose", async () => {
    await subscriber.stop();
    await publisher.close();
  });
  await app.listen({ host: runtimeHost(options), port: runtimePort(options) });
  return app;
}
