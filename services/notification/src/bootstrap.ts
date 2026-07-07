import { createApp, opentelemetryInstrumentationFromEnv, type InstrumentationHooks } from "./app.js";
import { startNotificationMessaging } from "./adapters/messaging/runtime.js";
import { startNotificationStorage } from "./adapters/storage/runtime.js";

export type BootstrapOptions = Readonly<{
  host?: string;
  port?: number;
  instrumentation?: InstrumentationHooks;
  messaging?: "enabled" | "disabled";
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
  const storage = process.env.DATABASE_URL ? await startNotificationStorage() : undefined;
  const app = createApp(options.instrumentation ?? opentelemetryInstrumentationFromEnv(), storage);
  const messaging = options.messaging === "disabled" ? undefined : await startNotificationMessaging(storage);
  app.addHook("onClose", async () => {
    await messaging?.stop();
    if (!messaging) {
      await storage?.stop();
    }
  });
  await app.listen({ host: runtimeHost(options), port: runtimePort(options) });
  return app;
}
