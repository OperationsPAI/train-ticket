import { dlqStreamKey, streamKey } from "@trainticket/ts-kit";

export { dlqStreamKey, streamKey };
export const OWN_STREAM = "events:offer-management";
export const OWN_DLQ_STREAM = "events:offer-management:dlq";
export const CONSUMER_GROUP = "offer-management";
export const SUBSCRIBED_STREAMS: readonly string[] = [
  "events:fare-pricing",
  "events:trip-planning",
  "events:traveler-profile",
  "events:transfer-management",
  "events:ancillary-service",
];

export function consumerName(instanceId?: string): string {
  return `offer-management-${instanceId ?? "default"}`;
}
