import { createEventEnvelope, type EventEnvelope, type EventPublisher } from "@trainticket/ts-kit";
import type { WaitlistEntrySnapshot } from "./domain.js";

export const WAITLIST_PRODUCER = "waitlist";

export type WaitlistEventPayload = Record<string, unknown>;

type EventOptions = Readonly<{ correlationId?: string; occurredAt?: Date | string }>;

export function waitlistRequestCreated(entry: WaitlistEntrySnapshot, options: EventOptions = {}): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistRequestCreated", entry, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    segmentRef: entry.segmentRef,
    travelClass: entry.travelClass,
    deadline: entry.deadline,
    paymentGuaranteeRef: entry.paymentGuaranteeRef,
    itineraryRef: entry.itineraryRef,
    intentFingerprint: entry.intentFingerprint,
    status: "DRAFT",
    createdAt: entry.createdAt,
  }, options);
}

export function waitlistPaymentAuthorizationRequested(entry: WaitlistEntrySnapshot, requestedAt: string, options: EventOptions = {}): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistPaymentAuthorizationRequested", entry, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    paymentGuaranteeRef: entry.paymentGuaranteeRef,
    requestedAt,
    status: entry.status,
  }, options);
}

export function waitlistQueued(entry: WaitlistEntrySnapshot, queuedAt: string, options: EventOptions & Readonly<{ requeueReason?: string }> = {}): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistQueued", entry, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    segmentRef: entry.segmentRef,
    travelClass: entry.travelClass,
    itineraryRef: entry.itineraryRef,
    intentFingerprint: entry.intentFingerprint,
    queuedAt,
    status: "QUEUED",
    journeyOrderRef: entry.journeyOrderRef,
    requeueReason: options.requeueReason,
  }, options);
}

export function waitlistMatchStarted(entry: WaitlistEntrySnapshot, matchedCapacityReleaseRef: string, startedAt: string, options: EventOptions = {}): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistMatchStarted", entry, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    segmentRef: entry.segmentRef,
    travelClass: entry.travelClass,
    matchedCapacityReleaseRef,
    journeyOrderIdempotencyKey: entry.journeyOrderIdempotencyKey,
    startedAt,
    status: "MATCHING",
  }, options);
}

export function waitlistHoldAuthorized(entry: WaitlistEntrySnapshot, authorizedAt: string, options: EventOptions = {}): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistHoldAuthorized", entry, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    segmentRef: entry.segmentRef,
    travelClass: entry.travelClass,
    authorizedAt,
    status: "MATCHING",
  }, options);
}

export function waitlistFulfilled(entry: WaitlistEntrySnapshot, journeyOrderRef: string, fulfilledAt: string, options: EventOptions = {}): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistFulfilled", entry, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    segmentRef: entry.segmentRef,
    travelClass: entry.travelClass,
    journeyOrderRef,
    fulfilledAt,
    status: "FULFILLED",
  }, options);
}

export function waitlistCancelled(entry: WaitlistEntrySnapshot, reason: string, cancelledAt: string, options: EventOptions = {}): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistCancelled", entry, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    segmentRef: entry.segmentRef,
    travelClass: entry.travelClass,
    cancelledAt,
    reason,
    status: "CANCELLED",
  }, options);
}

export function waitlistExpired(entry: WaitlistEntrySnapshot, expiredAt: string, options: EventOptions = {}): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistExpired", entry, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    segmentRef: entry.segmentRef,
    travelClass: entry.travelClass,
    deadline: entry.deadline,
    expiredAt,
    status: "EXPIRED",
  }, options);
}

export async function publishAll(publisher: EventPublisher, envelopes: readonly EventEnvelope[]): Promise<void> {
  for (const envelope of envelopes) await publisher.publish(envelope);
}

function waitlistEnvelope(eventType: string, entry: WaitlistEntrySnapshot, payload: WaitlistEventPayload, options: EventOptions): EventEnvelope<WaitlistEventPayload> {
  return createEventEnvelope({ eventId: deterministicEventId(eventType, entry), eventType, producer: WAITLIST_PRODUCER, payload: omitUndefined(payload), correlationId: options.correlationId, occurredAt: options.occurredAt });
}

function deterministicEventId(eventType: string, entry: WaitlistEntrySnapshot): string {
  return `waitlist:${eventType}:${entry.waitlistRequestId}:${entry.version}`;
}

function omitUndefined(payload: WaitlistEventPayload): WaitlistEventPayload {
  return Object.fromEntries(Object.entries(payload).filter(([, value]) => value !== undefined));
}
