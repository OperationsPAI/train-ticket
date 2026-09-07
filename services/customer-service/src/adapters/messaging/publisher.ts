import { RedisEventPublisher as KitRedisEventPublisher, createRedisClient } from "@trainticket/ts-kit";

import { DEFAULT_REDIS_URL } from "./stream-config.js";

/**
 * The default client MUST come from `createRedisClient`.
 *
 * This constructor used to call `new Redis(url)` directly, which opted out of
 * every resilience guarantee the shared kit provides: ioredis' default
 * `maxRetriesPerRequest: 20` flushed the command queue with
 * MaxRetriesPerRequestError after 20 reconnect attempts, no `error` listener
 * was attached (so failures surfaced only as "[ioredis] Unhandled error
 * event"), and the client fed no liveness component. That is the exact
 * combination that wedged `account` for 20 hours on 2026-09-06.
 *
 * The parameter is typed through the kit's own constructor because this
 * service pins a different ioredis minor than the kit's nested copy, so the
 * two `Redis` class types are structurally incompatible.
 */
export class RedisEventPublisher extends KitRedisEventPublisher {
  constructor(
    redis: ConstructorParameters<typeof KitRedisEventPublisher>[0] = createRedisClient(
      process.env.REDIS_URL ?? DEFAULT_REDIS_URL,
      {},
      "customer-service-publisher",
    ),
  ) {
    super(redis);
  }
}
