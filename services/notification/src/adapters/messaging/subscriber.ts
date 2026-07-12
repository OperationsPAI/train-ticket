import { RedisStreamEventSubscriber as KitRedisStreamEventSubscriber } from "@trainticket/ts-kit";

export class RedisStreamEventSubscriber extends KitRedisStreamEventSubscriber {
  constructor(redis?: ConstructorParameters<typeof KitRedisStreamEventSubscriber>[0]) {
    super(redis as never, undefined, { thrownHandlerErrors: "retry" });
  }
}
