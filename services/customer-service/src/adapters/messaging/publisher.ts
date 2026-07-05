import { Redis } from "ioredis";
import { RedisEventPublisher as KitRedisEventPublisher } from "@trainticket/ts-kit";

import { DEFAULT_REDIS_URL } from "./stream-config.js";

export class RedisEventPublisher extends KitRedisEventPublisher {
  constructor(redis: Redis = new Redis(process.env.REDIS_URL ?? DEFAULT_REDIS_URL)) {
    super(redis as never);
  }
}
