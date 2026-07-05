export const CUSTOMER_SERVICE_CONSUMER_GROUP = "customer-service";
export const CUSTOMER_SERVICE_SUBSCRIPTIONS = Object.freeze(["events:journey-order"]);
export const DEFAULT_REDIS_URL = "redis://localhost:6379";
export const STREAM_MAXLEN = 100_000;
export const RECOVERY_MIN_IDLE_MS = 60_000;
export const MAX_DELIVERY_ATTEMPTS = 5;

export function streamForProducer(producer: string): string {
  return `events:${producer}`;
}

export function dlqForStream(stream: string): string {
  return `${stream}:dlq`;
}
