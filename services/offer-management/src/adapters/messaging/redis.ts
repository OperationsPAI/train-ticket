import { Redis } from "ioredis";

import { RedisEventPublisher } from "./publisher.js";
import { RedisEventSubscriber } from "./subscriber.js";

export type RedisMessagingAdapters = Readonly<{
  publisher: RedisEventPublisher;
  subscriber: RedisEventSubscriber;
  close: () => Promise<void>;
}>;

export async function createRedisMessagingAdapters(redisUrl: string): Promise<RedisMessagingAdapters> {
  const publisherRedis = new Redis(redisUrl, { lazyConnect: true });
  const subscriberRedis = new Redis(redisUrl, { lazyConnect: true });
  await Promise.all([publisherRedis.connect(), subscriberRedis.connect()]);

  return {
    publisher: new RedisEventPublisher(publisherRedis),
    subscriber: new RedisEventSubscriber(subscriberRedis),
    close: async () => {
      await Promise.allSettled([publisherRedis.quit(), subscriberRedis.quit()]);
    },
  };
}
