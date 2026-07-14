import { createEventEnvelope, type EventEnvelope, type EventPublisher } from "@trainticket/ts-kit";
import type { WaitlistEntrySnapshot } from "./domain.js";

export const WAITLIST_PRODUCER = "waitlist";

export type WaitlistEventPayload = Record<string, unknown>;

export function waitlistEntryCreated(entry: WaitlistEntrySnapshot, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistRequestCreated", {
    ...basePayload(entry),
    deadline: entry.deadline,
    paymentGuaranteeRef: entry.paymentGuaranteeRef,
    itineraryRef: entry.itineraryRef,
    intentFingerprint: entry.intentFingerprint,
    status: "DRAFT",
    createdAt: entry.createdAt,
  }, correlationId);
}

export function waitlistEntryPromoted(entry: WaitlistEntrySnapshot, matchedCapacityReleaseRef: string, startedAt: string, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistMatchStarted", {
    ...basePayload(entry),
    matchedCapacityReleaseRef,
    journeyOrderIdempotencyKey: entry.journeyOrderIdempotencyKey,
    startedAt,
    status: "MATCHING",
  }, correlationId);
}

export function waitlistOfferExpired(entry: WaitlistEntrySnapshot, expiredAt: string, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistExpired", {
    ...basePayload(entry),
    deadline: entry.deadline,
    expiredAt,
    status: "EXPIRED",
  }, correlationId);
}

export function waitlistEntryAccepted(entry: WaitlistEntrySnapshot, orderId: string, fulfilledAt: string, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistFulfilled", {
    ...basePayload(entry),
    journeyOrderRef: orderId,
    fulfilledAt,
    status: "FULFILLED",
  }, correlationId);
}

export async function publishAll(publisher: EventPublisher, envelopes: readonly EventEnvelope[]): Promise<void> {
  for (const envelope of envelopes) await publisher.publish(envelope);
}

function basePayload(entry: WaitlistEntrySnapshot): WaitlistEventPayload {
  return {
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRefs[0] ?? "",
    segmentRef: entry.segmentRef,
    travelClass: entry.seatClass,
  };
}

function waitlistEnvelope(eventType: string, payload: WaitlistEventPayload, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return createEventEnvelope({ eventType, producer: WAITLIST_PRODUCER, payload, correlationId });
}
