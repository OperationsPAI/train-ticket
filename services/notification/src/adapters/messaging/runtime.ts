import { DeduplicatingEventHandler, fatalHandling, successfulHandling } from "../../application/messaging.js";
import { DirectSuccessGateway, type NotificationChannelGateway, NonConformantNotificationTrigger, NotificationApplicationService } from "../../application/notification-service.js";
import { NotificationAggregator, RateLimitExceeded } from "../../domain.js";
import { startNotificationStorage, type NotificationStorageRuntime } from "../storage/runtime.js";
import { RedisStreamEventPublisher } from "./publisher.js";
import { RedisStreamEventSubscriber } from "./subscriber.js";
import {
  NOTIFICATION_CONSUMER_GROUP,
  NOTIFICATION_SUBSCRIBED_STREAMS,
  notificationConsumerName,
} from "./stream-config.js";

export type NotificationMessagingRuntime = Readonly<{
  publisher: RedisStreamEventPublisher | undefined;
  subscriber: RedisStreamEventSubscriber;
  storage: NotificationStorageRuntime | undefined;
  stop: () => Promise<void>;
}>;

export async function startNotificationMessaging(
  existingStorage?: NotificationStorageRuntime,
  channelGateway: NotificationChannelGateway = new DirectSuccessGateway(),
): Promise<NotificationMessagingRuntime> {
  const storage = existingStorage ?? await optionalStorageRuntime(channelGateway);
  const publisher = storage ? undefined : new RedisStreamEventPublisher();
  const subscriber = new RedisStreamEventSubscriber();
  const application = publisher ? new NotificationApplicationService(publisher, undefined, channelGateway, undefined, undefined, undefined, new NotificationAggregator()) : undefined;
  const handler = new DeduplicatingEventHandler(async (envelope) => {
    try {
      if (storage) {
        const result = await storage.handleExternalTrigger(envelope);
        if (result === "retry") {
          throw new Error("Notification storage handler requested retry");
        }
        return result === "dlq" ? fatalHandling() : successfulHandling();
      }
      await application?.handleExternalTrigger(envelope);
      return successfulHandling();
    } catch (error) {
      if (error instanceof NonConformantNotificationTrigger) {
        return fatalHandling(error);
      }
      if (error instanceof RateLimitExceeded) {
        throw error;
      }
      throw error;
    }
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
    storage,
    stop: async () => {
      await subscriber.stop();
      await publisher?.close();
      await storage?.stop();
    },
  };
}

async function optionalStorageRuntime(channelGateway: NotificationChannelGateway): Promise<NotificationStorageRuntime | undefined> {
  if (!process.env.DATABASE_URL) {
    return undefined;
  }
  return startNotificationStorage(channelGateway);
}
