import { createEventEnvelope, type EventEnvelope, type EventPublisher } from "@trainticket/ts-kit";
import type { WaitlistEntrySnapshot } from "./domain.js";
import type { WaitlistOffer } from "./promotion.js";

export const WAITLIST_PRODUCER = "waitlist";

export type WaitlistEventPayload = Record<string, unknown>;

export function waitlistEntryCreated(entry: WaitlistEntrySnapshot, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistEntryCreated", { entry }, correlationId);
}

export function waitlistEntryPromoted(entry: WaitlistEntrySnapshot, offer: WaitlistOffer, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistMatchStarted", { entry, offer }, correlationId);
}

export function waitlistOfferExpired(entry: WaitlistEntrySnapshot, offer: WaitlistOffer, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistOfferExpired", { entry, offer }, correlationId);
}

export function waitlistEntryAccepted(entry: WaitlistEntrySnapshot, orderId: string, seatAssignment: unknown, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistFulfilled", { entry, orderId, seatAssignment }, correlationId);
}

export async function publishAll(publisher: EventPublisher, envelopes: readonly EventEnvelope[]): Promise<void> {
  for (const envelope of envelopes) await publisher.publish(envelope);
}

function waitlistEnvelope(eventType: string, payload: WaitlistEventPayload, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return createEventEnvelope({ eventType, producer: WAITLIST_PRODUCER, payload, correlationId });
}
