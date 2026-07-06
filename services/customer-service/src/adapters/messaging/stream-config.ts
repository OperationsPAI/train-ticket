import { dlqForStream as kitDlqForStream, streamForProducer } from "@trainticket/ts-kit";

export const CUSTOMER_SERVICE_CONSUMER_GROUP = "customer-service";
export const CUSTOMER_SERVICE_SUBSCRIPTIONS = Object.freeze(["events:journey-order", "events:post-sales"]);
export const DEFAULT_REDIS_URL = "redis://localhost:6379";
export const STREAM_MAXLEN = 100_000;
export const RECOVERY_MIN_IDLE_MS = 60_000;
export const MAX_DELIVERY_ATTEMPTS = 5;
export { streamForProducer };
export const dlqForStream = kitDlqForStream;
