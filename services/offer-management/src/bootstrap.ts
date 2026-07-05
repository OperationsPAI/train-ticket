import { createApp, opentelemetryInstrumentationFromEnv, type InstrumentationHooks } from "./app.js";

export type BootstrapOptions = Readonly<{
  host?: string;
  port?: number;
  instrumentation?: InstrumentationHooks;
  redisUrl?: string;
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
  const app = createApp(options.instrumentation ?? opentelemetryInstrumentationFromEnv());
  await app.listen({ host: runtimeHost(options), port: runtimePort(options) });
  return app;
}
