import { uuidV7, type EventEnvelope, type EventPublisher } from "@trainticket/ts-kit";
import type { WaitlistEntrySnapshot } from "./domain.js";

export const WAITLIST_PRODUCER = "waitlist";

export type WaitlistEventPayload = Record<string, unknown>;

export function waitlistRequestCreated(entry: WaitlistEntrySnapshot, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistRequestCreated", entry.version, {
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
  }, entry, correlationId);
}

export function waitlistPaymentAuthorizationRequested(entry: WaitlistEntrySnapshot, requestedAt: Date, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistPaymentAuthorizationRequested", entry.version, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    paymentGuaranteeRef: entry.paymentGuaranteeRef,
    requestedAt: requestedAt.toISOString(),
    status: entry.status,
  }, entry, correlationId);
}

export function waitlistQueued(entry: WaitlistEntrySnapshot, queuedAt: Date, correlationId?: string, options: { requeueReason?: string } = {}): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistQueued", entry.version, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    segmentRef: entry.segmentRef,
    travelClass: entry.travelClass,
    itineraryRef: entry.itineraryRef,
    intentFingerprint: entry.intentFingerprint,
    queuedAt: queuedAt.toISOString(),
    status: "QUEUED",
    journeyOrderRef: entry.journeyOrderRef,
    requeueReason: options.requeueReason,
  }, entry, correlationId);
}

export function waitlistMatchStarted(entry: WaitlistEntrySnapshot, matchedCapacityReleaseRef: string, startedAt: Date, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistMatchStarted", entry.version, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    segmentRef: entry.segmentRef,
    travelClass: entry.travelClass,
    matchedCapacityReleaseRef,
    journeyOrderIdempotencyKey: entry.journeyOrderIdempotencyKey,
    startedAt: startedAt.toISOString(),
    status: "MATCHING",
  }, entry, correlationId);
}

export function waitlistHoldAuthorized(entry: WaitlistEntrySnapshot, authorizedAt: Date, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistHoldAuthorized", entry.version, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    segmentRef: entry.segmentRef,
    travelClass: entry.travelClass,
    authorizedAt: authorizedAt.toISOString(),
    status: "MATCHING",
  }, entry, correlationId);
}

export function waitlistFulfilled(entry: WaitlistEntrySnapshot, fulfilledAt: Date, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistFulfilled", entry.version, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    segmentRef: entry.segmentRef,
    travelClass: entry.travelClass,
    journeyOrderRef: entry.journeyOrderRef,
    fulfilledAt: fulfilledAt.toISOString(),
    status: "FULFILLED",
  }, entry, correlationId);
}

export function waitlistCancelled(entry: WaitlistEntrySnapshot, cancelledAt: Date, reason: string, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistCancelled", entry.version, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    segmentRef: entry.segmentRef,
    travelClass: entry.travelClass,
    cancelledAt: cancelledAt.toISOString(),
    reason,
    status: "CANCELLED",
  }, entry, correlationId);
}

export function waitlistExpired(entry: WaitlistEntrySnapshot, expiredAt: Date, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistExpired", entry.version, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRef,
    segmentRef: entry.segmentRef,
    travelClass: entry.travelClass,
    deadline: entry.deadline,
    expiredAt: expiredAt.toISOString(),
    status: "EXPIRED",
  }, entry, correlationId);
}

export async function publishAll(publisher: EventPublisher, envelopes: readonly EventEnvelope[]): Promise<void> {
  for (const envelope of envelopes) await publisher.publish(envelope);
}

function waitlistEnvelope(eventType: string, version: number, payload: WaitlistEventPayload, entry: WaitlistEntrySnapshot, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return Object.freeze({
    eventId: `waitlist:${eventType}:${entry.waitlistRequestId}:${version}`,
    eventType,
    schemaVersion: 1,
    producer: WAITLIST_PRODUCER,
    causationId: `cmd-${uuidV7()}`,
    correlationId: correlationId ?? `corr-${uuidV7()}`,
    occurredAt: new Date().toISOString(),
    payload: Object.freeze(removeUndefined(payload)),
  });
}

function removeUndefined<T extends Record<string, unknown>>(value: T): T {
  return Object.fromEntries(Object.entries(value).filter(([, entry]) => entry !== undefined)) as T;
}
