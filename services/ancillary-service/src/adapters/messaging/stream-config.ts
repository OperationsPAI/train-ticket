export const OWN_STREAM = "events:ancillary-service";
export const OWN_DLQ_STREAM = "events:ancillary-service:dlq";
export const CONSUMER_GROUP = "ancillary-service";
export const SUBSCRIBED_STREAMS: readonly string[] = ["events:journey-order"];
export function consumerName(instanceId?: string): string { return `ancillary-service-${instanceId ?? "default"}`; }
