import { RedisStreamEventSubscriber as KitRedisStreamEventSubscriber, type DeadLetterEvidenceSink } from "@trainticket/ts-kit";

export class RedisStreamEventSubscriber extends KitRedisStreamEventSubscriber {
  constructor(redis?: ConstructorParameters<typeof KitRedisStreamEventSubscriber>[0], deadLetterEvidenceSink?: DeadLetterEvidenceSink) {
    super(redis as never, undefined, { thrownHandlerErrors: "retry", deadLetterEvidenceSink });
  }
}
