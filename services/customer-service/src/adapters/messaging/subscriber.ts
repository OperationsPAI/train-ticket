import {
  RedisEventSubscriber as PlatformRedisEventSubscriber,
  type EventHandler,
  type EventSubscriber,
  type SubscriberLoopFailureHandler,
} from "@trainticket/ts-kit";

export { PlatformRedisEventSubscriber as RedisEventSubscriber };
export type { SubscriberLoopFailureHandler };

type StreamSubscriberFactory = (stream: string) => EventSubscriber;

/**
 * Gives every upstream stream its own Redis subscriber and recovery loop.
 *
 * The shared subscriber intentionally recovers the streams passed to one
 * subscribe() call in sequence. Customer Service has several independent
 * upstreams, and a slow support-case projection on one stream must not keep
 * the other streams from reclaiming their pending entries. Isolating each
 * stream here also gives each blocking XREADGROUP its own Redis connection.
 */
export class CustomerServiceRedisEventSubscriber implements EventSubscriber {
  private readonly subscribers: EventSubscriber[] = [];
  private subscribed = false;

  constructor(
    private readonly factory: StreamSubscriberFactory = () => new PlatformRedisEventSubscriber(),
  ) {}

  async subscribe(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: EventHandler,
    signal?: AbortSignal,
  ): Promise<void> {
    if (this.subscribed) {
      throw new Error("Customer Service event subscriber has already been started");
    }
    this.subscribed = true;

    try {
      await Promise.all(streams.map(async (stream) => {
        const subscriber = this.factory(stream);
        this.subscribers.push(subscriber);
        await subscriber.subscribe(
          [stream],
          group,
          `${consumerName}-${consumerSuffix(stream)}`,
          handler,
          signal,
        );
      }));
    } catch (error) {
      await this.stop();
      throw error;
    }
  }

  async stop(): Promise<void> {
    const subscribers = this.subscribers.splice(0);
    await Promise.allSettled(subscribers.map(async (subscriber) => {
      if (subscriber.stop) {
        await subscriber.stop();
      } else if (subscriber.close) {
        await subscriber.close();
      }
    }));
  }
}

function consumerSuffix(stream: string): string {
  const suffix = stream.replace(/^events:/, "").replace(/[^a-zA-Z0-9_-]+/g, "-");
  return suffix || "stream";
}
