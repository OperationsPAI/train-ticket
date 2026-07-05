import { createApp, opentelemetryInstrumentationFromEnv, type InstrumentationHooks } from "./app.js";
import { InMemoryAccountRepository } from "./application.js";
import { RedisStreamEventPublisher } from "./adapters/messaging/publisher.js";

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
  const publisher = new RedisStreamEventPublisher();
  const app = createApp({
    instrumentation: options.instrumentation ?? opentelemetryInstrumentationFromEnv(),
    repository: new InMemoryAccountRepository(),
    publisher,
  });
  app.addHook("onClose", async () => {
    await publisher.close();
  });
  await app.listen({ host: runtimeHost(options), port: runtimePort(options) });
  return app;
}
