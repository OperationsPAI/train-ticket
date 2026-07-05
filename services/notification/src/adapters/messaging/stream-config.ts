export const NOTIFICATION_PRODUCER = "notification";
export const NOTIFICATION_CONSUMER_GROUP = "notification";
export const NOTIFICATION_SUBSCRIBED_STREAMS = Object.freeze([
  "events:journey-order",
  "events:booking-orchestration",
  "events:payment",
  "events:entitlement-ticketing",
  "events:post-sales",
]);

export function notificationConsumerName(instanceId = process.env.HOSTNAME ?? process.pid.toString()): string {
  return `notification-${instanceId}`;
}

export function redisUrl(): string {
  return process.env.REDIS_URL ?? "redis://localhost:6379";
}

export function streamForProducer(producer: string): string {
  return `events:${producer}`;
}

export function dlqStream(stream: string): string {
  return `${stream}:dlq`;
}
