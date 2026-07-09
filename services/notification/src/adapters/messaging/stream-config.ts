import { dlqForStream, redisUrl, streamForProducer } from "@trainticket/ts-kit";

export const NOTIFICATION_PRODUCER = "notification";
export const NOTIFICATION_CONSUMER_GROUP = "notification";
export const NOTIFICATION_SUBSCRIBED_STREAMS = Object.freeze([
  "events:journey-order",
  "events:booking-orchestration",
  "events:payment",
  "events:entitlement-ticketing",
  "events:post-sales",
  "events:wallet-promotion",
]);

export function notificationConsumerName(instanceId = process.env.HOSTNAME ?? process.pid.toString()): string {
  return `notification-${instanceId}`;
}

export { redisUrl, streamForProducer };
export const dlqStream = dlqForStream;
