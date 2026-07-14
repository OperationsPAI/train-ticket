import { createEventEnvelope, type EventEnvelope, type EventPublisher } from "@trainticket/ts-kit";
import type { WaitlistEntrySnapshot } from "./domain.js";
import type { WaitlistOffer } from "./promotion.js";

export const WAITLIST_PRODUCER = "waitlist";

export type WaitlistEventPayload = Record<string, unknown>;

export function waitlistEntryCreated(entry: WaitlistEntrySnapshot, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistRequestCreated", waitlistPayload(entry), correlationId);
}

export function waitlistEntryPromoted(entry: WaitlistEntrySnapshot, offer: WaitlistOffer, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistMatchStarted", { ...waitlistPayload(entry), offer }, correlationId);
}

export function waitlistOfferExpired(entry: WaitlistEntrySnapshot, offer: WaitlistOffer | undefined, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistExpired", { ...waitlistPayload(entry), ...(offer ? { offer } : {}) }, correlationId);
}

export function waitlistEntryAccepted(entry: WaitlistEntrySnapshot, orderId: string, seatAssignment: unknown, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistFulfilled", { ...waitlistPayload(entry), journeyOrderRef: orderId, seatAssignment }, correlationId);
}

export async function publishAll(publisher: EventPublisher, envelopes: readonly EventEnvelope[]): Promise<void> {
  for (const envelope of envelopes) await publisher.publish(envelope);
}

function waitlistPayload(entry: WaitlistEntrySnapshot): WaitlistEventPayload {
  return {
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRefs[0] ?? "",
    segmentRef: entry.segmentRef,
    travelClass: entry.seatClass,
    deadline: entry.deadline,
    paymentGuaranteeRef: entry.paymentGuaranteeRef,
    itineraryRef: entry.itineraryRef,
    intentFingerprint: entry.intentFingerprint,
    status: entry.status,
    ...(entry.journeyOrderRef ? { journeyOrderRef: entry.journeyOrderRef } : {}),
  };
}

function waitlistEnvelope(eventType: string, payload: WaitlistEventPayload, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return createEventEnvelope({ eventType, producer: WAITLIST_PRODUCER, payload, correlationId });
}
