import { DeduplicatingEventHandler, successfulHandling } from "../../application/messaging.js";
import { NotificationApplicationService } from "../../application/notification-service.js";
import { RedisStreamEventPublisher } from "./publisher.js";
import { RedisStreamEventSubscriber } from "./subscriber.js";
import {
  NOTIFICATION_CONSUMER_GROUP,
  NOTIFICATION_SUBSCRIBED_STREAMS,
  notificationConsumerName,
} from "./stream-config.js";

export type NotificationMessagingRuntime = Readonly<{
  publisher: RedisStreamEventPublisher;
  subscriber: RedisStreamEventSubscriber;
  stop: () => Promise<void>;
}>;

export async function startNotificationMessaging(): Promise<NotificationMessagingRuntime> {
  const publisher = new RedisStreamEventPublisher();
  const subscriber = new RedisStreamEventSubscriber();
  const application = new NotificationApplicationService(publisher);
  const handler = new DeduplicatingEventHandler(async (envelope) => {
    await application.handleExternalTrigger(envelope);
    return successfulHandling();
  });

  await subscriber.subscribe(
    NOTIFICATION_SUBSCRIBED_STREAMS,
    NOTIFICATION_CONSUMER_GROUP,
    notificationConsumerName(),
    (envelope) => handler.handle(envelope),
  );

  return {
    publisher,
    subscriber,
    stop: async () => {
      await subscriber.stop();
      await publisher.close();
    },
  };
}
