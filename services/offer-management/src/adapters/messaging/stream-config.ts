// ---------------------------------------------------------------------------
// Stream configuration — stream names, group names, consumer naming
// Lives ONLY under adapters/messaging/ — centralizes all stream-key strings.
// ---------------------------------------------------------------------------

/**
 * Returns the stream key for a given producing context.
 */
export function streamKey(context: string): string {
  return `events:${context}`;
}

/**
 * Returns the dead-letter stream key for a given producing context.
 */
export function dlqStreamKey(context: string): string {
  return `events:${context}:dlq`;
}

/**
 * The stream that offer-management publishes to.
 */
export const OWN_STREAM = "events:offer-management";

/**
 * The dead-letter stream for offer-management.
 */
export const OWN_DLQ_STREAM = "events:offer-management:dlq";

/**
 * The consumer group name for offer-management.
 */
export const CONSUMER_GROUP = "offer-management";

/**
 * The streams that offer-management subscribes to, per messaging.md subscription table.
 * Rows 6 (events:fare-pricing), 7 (events:trip-planning), 37 (events:traveler-profile).
 */
export const SUBSCRIBED_STREAMS: readonly string[] = [
  "events:fare-pricing",
  "events:trip-planning",
  "events:traveler-profile",
];

/**
 * Generate a consumer name for this instance.
 */
export function consumerName(instanceId?: string): string {
  return `offer-management-${instanceId ?? "default"}`;
}
